package org.jetbrains.plugins.scala.lang.psi.impl.base.types

import api.statements.{ScTypeAliasDeclaration, ScTypeAliasDefinition, ScTypeAlias}
import com.intellij.lang.ASTNode
import com.intellij.psi._
import tree.{IElementType, TokenSet}
import api.base.types._
import api.base.ScReferenceElement
import psi.ScalaPsiElementImpl
import lexer.ScalaTokenTypes
import scala.lang.resolve.ScalaResolveResult
import psi.types._
import api.toplevel.ScPolymorphicElement
import psi.impl.toplevel.synthetic.ScSyntheticClass

/** 
* @author Alexander Podkhalyuzin
* Date: 22.02.2008
*/

class ScSimpleTypeElementImpl(node: ASTNode) extends ScalaPsiElementImpl(node) with ScSimpleTypeElement {

  override def toString: String = "SimpleTypeElement"

  def singleton = getNode.findChildByType(ScalaTokenTypes.kTYPE) != null

  override def getType() = {
    if (singleton) new ScSingletonType(pathElement) else reference match {
      case Some(ref) => ref.qualifier match {
        case None => ref.bind match {
          case None => Nothing
          case Some(ScalaResolveResult(e, s)) => e match {
            case aliasDef: ScTypeAliasDefinition =>
              if (aliasDef.typeParameters == 0) s.subst(aliasDef.aliasedType) else new ScTypeConstructorType(aliasDef, s)
            case alias: ScTypeAliasDeclaration => new ScTypeAliasType(alias, s)
            case tp: PsiTypeParameter => ScalaPsiManager.typeVariable(tp)
            case synth: ScSyntheticClass => synth.t
            case _ => new ScDesignatorType(e)
          }
        }
        case Some(q) => new ScProjectionType(new ScSingletonType(q), ref)
      }
      case None => Nothing
    }
  }
}