package org.jetbrains.plugins.scala
package lang
package completion
package filters.expression

import com.intellij.psi.filters.ElementFilter
import com.intellij.psi.{PsiElement, _}
import org.jetbrains.annotations.NonNls
import org.jetbrains.plugins.scala.lang.completion.ScalaCompletionUtil._
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScArguments

/**
* @author Alexander Podkhalyuzin
* Date: 22.05.2008
*/

class CatchFilter extends ElementFilter {
  def isAcceptable(element: Object, context: PsiElement): Boolean = {
    if (context.isInstanceOf[PsiComment]) return false
    val (leaf, _) = processPsiLeafForFilter(getLeafByOffset(context.getTextRange.getStartOffset, context))
    if (leaf != null) {
      var i = getPrevNotWhitespaceAndComment(context.getTextRange.getStartOffset - 1, context)
      var leaf1 = getLeafByOffset(i, context)
      if (leaf1.getNode.getElementType == ScalaTokenTypes.kTRY) return false
      val prevIsRBrace = leaf1.getText == "}"
      val prevIsRParan = leaf1.getText == ")"
      while (leaf1 != null && !leaf1.isInstanceOf[ScTry]) {
        leaf1 match {
          case _: ScFinallyBlock =>
            return false
          case _: ScParenthesisedExpr | _: ScArguments if !prevIsRParan =>
            return false
          case _: ScBlock if !prevIsRBrace =>
           return false
          case _ =>
        }
        leaf1 = leaf1.getParent
      }
      if (leaf1 == null) return false
      if (leaf1.getTextRange.getEndOffset != i + 1) return false
      i = getNextNotWhitespaceAndComment(context.getTextRange.getEndOffset, context)
      if (leaf1.asInstanceOf[ScTry].catchBlock.isDefined) return false
      if ("catch" == getLeafByOffset(i, context).getText) return false
      return true
    }

    false
  }

  def isClassAcceptable(hintClass: java.lang.Class[_]): Boolean = true

  @NonNls
  override def toString = "statements keyword filter"
}