package org.jetbrains.plugins.scala.build

import java.util
import java.util.UUID

import com.intellij.build.events.impl._
import com.intellij.build.events._
import com.intellij.build.{BuildViewManager, DefaultBuildDescriptor, FilePosition, events}
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.pom.Navigatable
import com.intellij.task.ProjectTaskResult
import javax.swing.JComponent
import org.jetbrains.plugins.scala.build.BuildMessages._

import scala.collection.JavaConverters._

trait BuildReporter {
  def start()

  def finish(messages: BuildMessages): Unit
  def finishWithFailure(err: Throwable): Unit
  def finishCanceled(): Unit

  def warning(message: String, position: Option[FilePosition]): Unit
  def error(message: String, position: Option[FilePosition]): Unit
  def info(message: String, position: Option[FilePosition]): Unit

  def log(message: String): Unit
}

class IndicatorReporter(indicator: ProgressIndicator) extends BuildReporter {
  override def start(): Unit = {
    indicator.setText("build running ...")
  }

  override def finish(messages: BuildMessages): Unit = {
    indicator.setText2("")

    if (messages.errors.isEmpty)
      indicator.setText("build completed")
    else
      indicator.setText("build failed")
  }

  override def finishWithFailure(err: Throwable): Unit = {
    indicator.setText(s"errored: ${err.getMessage}")
  }

  override def finishCanceled(): Unit = {
    indicator.setText("canceled")
  }


  override def warning(message: String, position: Option[FilePosition]): Unit = {
    indicator.setText(s"WARNING: $message")
    indicator.setText2(positionString(position))
  }

  override def error(message: String, position: Option[FilePosition]): Unit = {
    indicator.setText(s"ERROR: $message")
    indicator.setText2(positionString(position))
  }

  override def log(message: String): Unit = {
    indicator.setText("building ...")
    indicator.setText2(message)
  }

  override def info(message: String, position: Option[FilePosition]): Unit = {
    indicator.setText(message)
    indicator.setText2(positionString(position))
  }

  private def positionString(position: Option[FilePosition]) = {
    position.fold("") { pos =>
      s"${pos.getFile} [${pos.getStartLine}:${pos.getStartColumn}]"
    }
  }
}

class BuildToolWindowReporter(project: Project, buildId: EventId, title: String) extends BuildReporter {
  import MessageEvent.Kind

  private lazy val viewManager: BuildViewManager = ServiceManager.getService(project, classOf[BuildViewManager])

  override def start(): Unit = {
    val buildDescriptor = new DefaultBuildDescriptor(buildId, title, project.getBasePath, System.currentTimeMillis())
    val startEvent = new StartBuildEventImpl(buildDescriptor, "running ...")
      .withContentDescriptorSupplier { () => // dummy runContentDescriptor to set autofocus of build toolwindow off
        val descriptor = new RunContentDescriptor(null, null, new JComponent {}, title)
        descriptor.setActivateToolWindowWhenAdded(false)
        descriptor.setAutoFocusContent(false)
        descriptor
      }

    viewManager.onEvent(buildId, startEvent)
  }

  override def finish(messages: BuildMessages): Unit = {
    val (result, resultMessage) =
      if (messages.status == BuildMessages.OK && messages.errors.isEmpty)
        (new SuccessResultImpl, "success")
      else if (messages.status == BuildMessages.Canceled)
        (new SkippedResultImpl, "canceled")
      else {
        val fails: util.List[events.Failure] = messages.errors.asJava
        (new FailureResultImpl(fails), "failed")
      }

    val finishEvent =
      new FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), resultMessage, result)
    viewManager.onEvent(buildId, finishEvent)
  }

  override def finishWithFailure(err: Throwable): Unit = {
    val failureResult = new FailureResultImpl(err)
    val finishEvent =
      new FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "failed", failureResult)
    viewManager.onEvent(buildId, finishEvent)
  }

  override def finishCanceled(): Unit = {
    val canceledResult = new SkippedResultImpl
    val finishEvent =
      new FinishBuildEventImpl(buildId, null, System.currentTimeMillis(), "canceled", canceledResult)
    viewManager.onEvent(buildId, finishEvent)
  }

  def startTask(taskId: EventId, parent: Option[EventId], message: String, time: Long = System.currentTimeMillis()): Unit = {
    val startEvent = new StartEventImpl(taskId, parent.orNull, time, message)
    viewManager.onEvent(buildId, startEvent)
  }

  def progressTask(taskId: EventId, total: Long, progress: Long, unit: String, message: String, time: Long = System.currentTimeMillis()): Unit = {
    val time = System.currentTimeMillis() // TODO pass as parameter?
    val unitOrDefault = if (unit == null) "items" else unit
    val event = new ProgressBuildEventImpl(taskId, null, time, message, total, progress, unitOrDefault)
    viewManager.onEvent(buildId, event)
  }

  def finishTask(taskId: EventId, message: String, result: EventResult, time: Long = System.currentTimeMillis()): Unit = {
    val time = System.currentTimeMillis() // TODO pass as parameter?
    val event = new FinishEventImpl(taskId, null, time, message, result)
    viewManager.onEvent(buildId, event)
  }

  override def warning(message: String, position: Option[FilePosition]): Unit =
    viewManager.onEvent(buildId, event(message, Kind.WARNING, position))

  override def error(message: String, position: Option[FilePosition]): Unit =
    viewManager.onEvent(buildId, event(message, Kind.ERROR, position))

  override def info(message: String, position: Option[FilePosition]): Unit =
    viewManager.onEvent(buildId, event(message, Kind.INFO, position))

  override def log(message: String): Unit =
    viewManager.onEvent(buildId, logEvent(message))

  private def logEvent(msg: String): BuildEvent =
    new OutputBuildEventImpl(buildId, msg.trim + System.lineSeparator(), true)

  private def event(message: String, kind: MessageEvent.Kind, position: Option[FilePosition])=
    BuildMessages.message(buildId, message, kind, position)

}


class BuildEventMessage(parentId: Any, kind: MessageEvent.Kind, group: String, message: String)
  extends AbstractBuildEvent(new Object, parentId, System.currentTimeMillis(), message) with MessageEvent {

  override def getKind: MessageEvent.Kind = kind

  override def getGroup: String = group

  override def getResult: MessageEventResult =
    new MessageEventResult() {
      override def getKind: MessageEvent.Kind = kind
    }

  override def getNavigatable(project: Project): Navigatable = null // TODO sensible default navigation?
}


case class BuildMessages(warnings: Seq[events.Warning], errors: Seq[events.Failure], log: Seq[String], status: BuildStatus) {
  def appendMessage(text: String): BuildMessages = copy(log = log :+ text.trim)
  def addError(msg: String): BuildMessages = copy(errors = errors :+ BuildFailure(msg.trim))
  def addWarning(msg: String): BuildMessages = copy(warnings = warnings :+ BuildWarning(msg.trim))
  def status(buildStatus: BuildStatus): BuildMessages = copy(status = buildStatus)
  def toTaskResult: ProjectTaskResult =
    new ProjectTaskResult(
      status == Canceled || status == Error,
      errors.size,
      warnings.size
    )
}

case object BuildMessages {

  sealed abstract class BuildStatus
  case object Indeterminate extends BuildStatus
  case object OK extends BuildStatus
  case object Error extends BuildStatus
  case object Canceled extends BuildStatus


  case class EventId(id: String)

  def randomEventId: EventId = EventId(UUID.randomUUID().toString)

  def empty = BuildMessages(Vector.empty, Vector.empty, Vector.empty, BuildMessages.Indeterminate)

  def message(parentId: Any, message: String, kind: MessageEvent.Kind, position: Option[FilePosition]): AbstractBuildEvent with MessageEvent =
    position match {
      case None => new BuildEventMessage(parentId, kind, kind.toString, message)
      case Some(filePosition) => new FileMessageEventImpl(parentId, kind, kind.toString, message, null, filePosition)
    }
}

case class BuildFailure(message: String) extends events.impl.FailureImpl(message, /*description*/ null: String)

case class BuildWarning(message: String) extends Warning {
  override def getMessage: String = message
  override def getDescription: String = null
}

case class BuildFailureException(msg: String) extends Exception(msg)