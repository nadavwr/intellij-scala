package org.jetbrains.plugins.scala
package console

import com.intellij.execution.configurations.{ConfigurationFactory, ConfigurationType}
import com.intellij.openapi.project.DumbAware
import javax.swing.Icon
import org.jetbrains.plugins.scala.icons.Icons

class ScalaConsoleConfigurationType extends ConfigurationType with DumbAware {
  private val confFactory = new ScalaConsoleRunConfigurationFactory(this)

  def getIcon: Icon = Icons.SCALA_CONSOLE

  def getDisplayName: String = "Scala Console"

  def getConfigurationTypeDescription: String = "Scala console run configurations"

  def getConfigurationFactories: Array[ConfigurationFactory] = Array[ConfigurationFactory](confFactory)

  def getId: String = "ScalaScriptConsoleRunConfiguration"
}