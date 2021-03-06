package org.jetbrains.plugins.scala.testingSupport.test

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.psi.PsiClass
import org.jetbrains.plugins.scala.extensions.PsiClassExt
import org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil
import org.jetbrains.plugins.scala.testingSupport.test.TestRunConfigurationForm.TestKind

class ClassTestData(config: AbstractTestRunConfiguration) extends TestConfigurationData(config) {

  protected[test] def getClassPathClazz: PsiClass = config.getClazz(getTestClassPath, withDependencies = false)

  override def checkSuiteAndTestName(): Unit = {
    checkModule()
    if (getTestClassPath == "") {
      throw new RuntimeConfigurationException("Test Class is not specified")
    }
    val clazz = getClassPathClazz
    if (clazz == null || config.isInvalidSuite(clazz)) {
      if (clazz != null && !ScalaPsiUtil.isInheritorDeep(clazz, config.getSuiteClass)) {
        throw new RuntimeConfigurationException("Class %s is not inheritor of Suite trait".format(getTestClassPath))
      } else {
        throw new RuntimeConfigurationException("No Suite Class is found for Class %s in module %s".
          format(getTestClassPath,
            getModule.getName))
      }
    }
  }

  override def getTestMap: Map[String, Set[String]] = {
    if (isDumb) return Map(testClassPath -> Set[String]())
    val clazz = getClassPathClazz
    if (clazz == null) config.classNotFoundError
    if (config.isInvalidSuite(clazz)) throw new ExecutionException(s"$clazz is not a valid test suite")
    Map(clazz.qualifiedName -> Set[String]())
  }

  override def getKind: TestKind = TestKind.CLASS

  override def apply(form: TestRunConfigurationForm): Unit = {
    super.apply(form)
    testClassPath = form.getTestClassPath
  }
}

object ClassTestData {
  def apply(config: AbstractTestRunConfiguration, className: String): ClassTestData = apply(config, className, null)
  def apply(config: AbstractTestRunConfiguration, className: String, testName: String): ClassTestData = {
    if (testName != null && testName != "") {
      val res = new SingleTestData(config)
      res.setTestClassPath(className)
      res.setTestName(testName)
      res
    } else {
      val res = new ClassTestData(config)
      res.setTestClassPath(className)
      res
    }
  }
}