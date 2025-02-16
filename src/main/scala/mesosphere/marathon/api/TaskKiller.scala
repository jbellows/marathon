package mesosphere.marathon.api

import javax.inject.Inject

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.task.tracker.TaskTracker
import mesosphere.marathon.state._
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.marathon.{ MarathonSchedulerService, UnknownAppException }

import scala.concurrent.Future

class TaskKiller @Inject() (
    taskTracker: TaskTracker,
    groupManager: GroupManager,
    service: MarathonSchedulerService) {

  def kill(
    appId: PathId,
    findToKill: (Iterable[MarathonTask] => Iterable[MarathonTask])): Future[Iterable[MarathonTask]] = {

    if (taskTracker.hasAppTasksSync(appId)) {
      val tasks = taskTracker.appTasksSync(appId)
      val toKill = findToKill(tasks)
      service.killTasks(appId, toKill)
      Future.successful(toKill)
    }
    else {
      //The task manager does not know about apps with 0 tasks
      //prior versions of Marathon accepted this call with an empty iterable
      import scala.concurrent.ExecutionContext.Implicits.global
      groupManager.app(appId).map {
        case Some(app) => Iterable.empty[MarathonTask]
        case None      => throw new UnknownAppException(appId)
      }
    }
  }

  def killAndScale(appId: PathId,
                   findToKill: (Iterable[MarathonTask] => Iterable[MarathonTask]),
                   force: Boolean): Future[DeploymentPlan] = {
    killAndScale(Map(appId -> findToKill(taskTracker.appTasksSync(appId))), force)
  }

  def killAndScale(appTasks: Map[PathId, Iterable[MarathonTask]], force: Boolean): Future[DeploymentPlan] = {
    def scaleApp(app: AppDefinition): AppDefinition = {
      appTasks.get(app.id).fold(app) { toKill => app.copy(instances = app.instances - toKill.size) }
    }
    def updateGroup(group: Group): Group = {
      group.copy(apps = group.apps.map(scaleApp), groups = group.groups.map(updateGroup))
    }
    def killTasks = groupManager.update(PathId.empty, updateGroup, Timestamp.now(), force = force, toKill = appTasks)
    appTasks.keys.find(id => !taskTracker.hasAppTasksSync(id))
      .map(id => Future.failed(UnknownAppException(id)))
      .getOrElse(killTasks)
  }
}
