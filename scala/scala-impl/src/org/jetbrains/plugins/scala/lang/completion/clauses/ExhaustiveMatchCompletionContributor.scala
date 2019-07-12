package org.jetbrains.plugins.scala
package lang
package completion
package clauses

import com.intellij.codeInsight.completion._
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.patterns.{ElementPattern, PlatformPatterns}
import com.intellij.psi._
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.psi.api.base.patterns._
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.types.ScType

final class ExhaustiveMatchCompletionContributor extends ScalaCompletionContributor {

  import ExhaustiveMatchCompletionContributor._
  import PlatformPatterns.psiElement

  extend(
    referenceWithParent(psiElement(classOf[ScPostfixExpr]), psiElement(classOf[ScInfixExpr]))
  ) {
    new ExhaustiveClauseCompletionProvider[ScSugarCallExpr](ScalaKeyword.MATCH) {

      override protected def targetType(call: ScSugarCallExpr)
                                       (implicit place: PsiElement): Option[ScType] = call match {
        case _: ScPrefixExpr => None
        case ScSugarCallExpr(operand, operation, _) if operation.isAncestorOf(place) => operand.`type`().toOption
        case _ => None
      }

      override protected def createInsertHandler(strategy: PatternGenerationStrategy)
                                                (implicit place: PsiElement) =
        new ExhaustiveClauseInsertHandler[ScMatch](strategy, None, None)
    }
  }

  extend(
    leaf.withParent(classOf[ScMatch])
  ) {
    new ExhaustiveClauseCompletionProvider[ScMatch]() {

      override protected def targetType(`match`: ScMatch)
                                       (implicit place: PsiElement): Option[ScType] =
        expectedMatchType(`match`)

      override protected def createInsertHandler(strategy: PatternGenerationStrategy)
                                                (implicit place: PsiElement) =
        new ExhaustiveClauseInsertHandler[ScMatch](strategy)
    }
  }

  extend(
    leaf.withParents(classOf[ScReferenceExpression], classOf[ScBlock], classOf[ScArgumentExprList], classOf[ScMethodCall])
  ) {
    new ExhaustiveClauseCompletionProvider[ScBlockExpr]() {

      override protected def targetType(block: ScBlockExpr)
                                       (implicit place: PsiElement): Option[ScType] =
        expectedFunctionalType(block)

      override protected def createInsertHandler(strategy: PatternGenerationStrategy)
                                                (implicit place: PsiElement) =
        new ExhaustiveClauseInsertHandler[ScBlockExpr](strategy)
    }
  }

  private def extend(place: ElementPattern[_ <: PsiElement])
                    (provider: ExhaustiveClauseCompletionProvider[_]): Unit =
    extend(CompletionType.BASIC, place, provider)
}

object ExhaustiveMatchCompletionContributor {

  private[lang] val Exhaustive = "exhaustive"

  private[lang] def rendererTailText = "(" + Exhaustive + ")"

  private abstract class ExhaustiveClauseCompletionProvider[
    E <: ScExpression : reflect.ClassTag,
  ](keywordLookupString: String = ScalaKeyword.CASE) extends ClauseCompletionProvider[E] {

    override final protected def addCompletions(expression: E, result: CompletionResultSet)
                                               (implicit parameters: ClauseCompletionParameters): Unit = for {
      PatternGenerationStrategy(strategy) <- targetType(expression)(parameters.place)

      lookupElement = buildLookupElement(
        keywordLookupString,
        createInsertHandler(strategy)(parameters.place)
      ) {
        case (_, presentation: LookupElementPresentation) =>
          presentation.setItemText(keywordLookupString)
          presentation.setItemTextBold(true)

          presentation.setTailText(" ", true)
          presentation.appendTailText(rendererTailText, true)
      }
    } result.addElement(lookupElement)

    protected def targetType(expression: E)
                            (implicit place: PsiElement): Option[ScType]

    protected def createInsertHandler(strategy: PatternGenerationStrategy)
                                     (implicit place: PsiElement): ExhaustiveClauseInsertHandler[_]
  }

  private final class ExhaustiveClauseInsertHandler[
    E <: ScExpression : reflect.ClassTag
  ](strategy: PatternGenerationStrategy,
    prefix: Option[String] = Some(""),
    suffix: Option[String] = Some(""))
   (implicit place: PsiElement) extends ClauseInsertHandler[E] {

    override protected def handleInsert(implicit context: InsertionContext): Unit = {
      val (components, clausesText) = strategy.createClauses(prefix, suffix)
      replaceText(clausesText)

      onTargetElement { statement: E =>
        val caseClauses = statement.findLastChildByType[ScCaseClauses](parser.ScalaElementType.CASE_CLAUSES)

        val clauses = caseClauses.caseClauses
        strategy.adjustTypes(components, clauses)

        reformatAndMoveCaret(caseClauses, clauses.head, statement.getTextRange)
      }
    }
  }
}
