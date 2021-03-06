package org.jetbrains.plugins.scala
package lang
package resolve
package processor

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi._
import com.intellij.psi.scope._
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.lang.psi.api.expr.{ScSuperReference, ScThisReference}
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScTypeAlias, ScTypeAliasDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScObject, ScTrait}
import org.jetbrains.plugins.scala.lang.psi.impl.ScPackageImpl
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaNamesUtil
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveState.ResolveStateExt
import org.jetbrains.plugins.scala.lang.resolve.processor.precedence._
import org.jetbrains.plugins.scala.util.ScEquivalenceUtil

import scala.collection.mutable.ArrayBuffer
import scala.collection.{Set, mutable}

class ResolveProcessor(override val kinds: Set[ResolveTargets.Value],
                       val ref: PsiElement,
                       val name: String) extends BaseProcessor(kinds)(ref) with PrecedenceHelper {

  import ResolveProcessor._

  private object ResolveStrategy extends NameUniquenessStrategy {

    override def isValid(result: ScalaResolveResult): Boolean = result.qualifiedNameId != null

    override def computeHashCode(result: ScalaResolveResult): Int = result.qualifiedNameId match {
      case null => 0
      case id => id.hashCode
    }

    override def equals(left: ScalaResolveResult, right: ScalaResolveResult): Boolean =
      left.qualifiedNameId == right.qualifiedNameId
  }

  @volatile
  private var resolveScope: GlobalSearchScope = null

  private val history = new ArrayBuffer[HistoryEvent]
  private var fromHistory: Boolean = false

  def getResolveScope: GlobalSearchScope = {
    if (resolveScope == null) {
      resolveScope = ref.resolveScope
    }
    resolveScope
  }

  private val ignoredSet = new mutable.HashSet[ScalaResolveResult]()

  def getPlace: PsiElement = ref

  val isThisOrSuperResolve = ref.getParent match {
    case _: ScThisReference | _: ScSuperReference => true
    case _ => false
  }

  def emptyResultSet: Boolean = candidatesSet.isEmpty || levelSet.isEmpty

  override protected def nameUniquenessStrategy: NameUniquenessStrategy = ResolveStrategy

  override protected val holder: SimpleTopPrecedenceHolder = new SimpleTopPrecedenceHolder

  /**
    * This method useful for resetting precednce if we dropped
    * all found candidates to seek implicit conversion candidates.
    */
  def resetPrecedence(): Unit = holder.reset()

  import PrecedenceTypes._

  def checkImports(): Boolean = checkPrecedence(IMPORT)

  def checkWildcardImports(): Boolean = checkPrecedence(WILDCARD_IMPORT)

  def checkPredefinedClassesAndPackages(): Boolean = checkPrecedence(SCALA_PREDEF)

  private def checkPrecedence(i: Int) =
    holder.currentPrecedence <= i

  override def changedLevel: Boolean = {
    if (!fromHistory && !history.lastOption.contains(ChangedLevel)) {
      history += ChangedLevel
    }

    def update: Boolean = {
      val iterator = levelSet.iterator()
      while (iterator.hasNext) {
        candidatesSet += iterator.next()
      }
      uniqueNamesSet.addAll(levelUniqueNamesSet)
      levelSet.clear()
      levelUniqueNamesSet.clear()
      false
    }

    if (levelSet.isEmpty) true
    else if (holder.currentPrecedence == OTHER_MEMBERS) update
    else !update
  }

  def isAccessible(named: PsiNamedElement, place: PsiElement): Boolean = {
    val memb: PsiMember = {
      named match {
        case memb: PsiMember => memb
        case _ => ScalaPsiUtil.nameContext(named) match {
          case memb: PsiMember => memb
          case _ => return true //something strange
        }
      }
    }
    ResolveUtils.isAccessible(memb, place)
  }

  override protected def execute(namedElement: PsiNamedElement)
                                (implicit state: ResolveState): Boolean = {
    def renamed: Option[String] = state.renamed

    if (nameMatches(namedElement)) {
      val accessible = isAccessible(namedElement, ref)
      if (accessibility && !accessible) return true
      namedElement match {
        case o: ScObject if o.isPackageObject && JavaPsiFacade.getInstance(namedElement.getProject).
          findPackage(o.qualifiedName) != null =>
        case pack: PsiPackage =>
          val resolveResult: ScalaResolveResult =
            new ScalaResolveResult(ScPackageImpl(pack), state.substitutor, state.importsUsed, renamed, isAccessible = accessible)
          addResult(resolveResult)
        case clazz: PsiClass if !isThisOrSuperResolve || PsiTreeUtil.isContextAncestor(clazz, ref, true) =>
          addResult(new ScalaResolveResult(namedElement, state.substitutor,
            state.importsUsed, renamed, fromType = state.fromType, isAccessible = accessible))
        case _: PsiClass => //do nothing, it's wrong class or object
        case _ if isThisOrSuperResolve => //do nothing for type alias
        case _ =>
          addResult(new ScalaResolveResult(namedElement, state.substitutor,
            state.importsUsed, renamed, fromType = state.fromType, isAccessible = accessible))
      }
    }

    true
  }

  protected final def nameMatches(namedElement: PsiNamedElement)
                                 (implicit state: ResolveState): Boolean = {
    val elementName = state.renamed.getOrElse(namedElement.name)

    !StringUtil.isEmpty(elementName) && ScalaNamesUtil.equivalent(elementName, name)
  }

  override def getHint[T](hintKey: Key[T]): T = {
    hintKey match {
      case NameHint.KEY if name != "" => ScalaNameHint.asInstanceOf[T]
      case _ => super.getHint(hintKey)
    }
  }

  override protected def addResults(results: Seq[ScalaResolveResult]): Boolean = {
    if (!fromHistory) history += AddResult(results)
    super.addResults(results)
  }

  override protected def ignored(results: Seq[ScalaResolveResult]): Boolean = {
    val result = !fromHistory && super.ignored(results)

    if (result) {
      ignoredSet ++= results
    }

    result
  }

  override protected def clear(): Unit = {
    ignoredSet.clear()
    candidatesSet = Set.empty
    super.clear()

    fromHistory = true
    try {
      history.foreach {
        case ChangedLevel => changedLevel
        case AddResult(results) => addResults(results)
      }
    }
    finally {
      fromHistory = false
    }
  }

  override def candidatesS: Set[ScalaResolveResult] = {
    var res = candidatesSet
    val iterator = levelSet.iterator()
    while (iterator.hasNext) {
      res += iterator.next()
    }
    if (!ResolveProcessor.compare(ignoredSet, res)) {
      res = Set.empty
      clear()
      //now let's add everything again
      val iterator = levelSet.iterator()
      while (iterator.hasNext) {
        res += iterator.next()
      }
    }

    /*
    This is also hack for self type elements to filter duplicates.
    For example:
    trait IJTest {
      self : MySub =>
      type FooType
      protected implicit def d: FooType
    }
    trait MySub extends IJTest {
      type FooType = Long
    }
     */
    res.filter {
      case r@ScalaResolveResult(_: ScTypeAlias | _: ScClass | _: ScTrait, _) =>
        res.foldLeft(true) {
          case (false, _) => false
          case (true, rr@ScalaResolveResult(_: ScTypeAlias | _: ScClass | _: ScTrait, _)) =>
            rr.element.name != r.element.name ||
              !ScalaPsiUtil.superTypeMembers(rr.element).contains(r.element)
          case (true, _) => true
        }
      case _ => true
    }
  }

  object ScalaNameHint extends NameHint {
    def getName(state: ResolveState): String = state.renamed.getOrElse(name)
  }

  override def toString = s"ResolveProcessor($name)"
}

object ResolveProcessor {

  private sealed trait HistoryEvent

  private case object ChangedLevel extends HistoryEvent

  private case class AddResult(results: Seq[ScalaResolveResult]) extends HistoryEvent

  private def compare(ignoredSet: Set[ScalaResolveResult], set: Set[ScalaResolveResult]): Boolean = {
    if (ignoredSet.nonEmpty && set.isEmpty) return false

    ignoredSet.forall { ignored =>
      set.forall { result =>
        areEquivalent(ignored.getActualElement, result.getActualElement)
      }
    }
  }

  private[this] def areEquivalent(left: PsiNamedElement, right: PsiNamedElement): Boolean =
    ScEquivalenceUtil.smartEquivalence(left, right) ||
      isExactAliasFor(left, right) || isExactAliasFor(right, left)

  private[this] def isExactAliasFor(left: PsiNamedElement, right: PsiNamedElement): Boolean =
    left.isInstanceOf[ScTypeAliasDefinition] &&
      right.isInstanceOf[PsiClass] &&
      left.asInstanceOf[ScTypeAliasDefinition].isExactAliasFor(right.asInstanceOf[PsiClass])
}
