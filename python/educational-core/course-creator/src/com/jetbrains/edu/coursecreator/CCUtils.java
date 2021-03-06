package com.jetbrains.edu.coursecreator;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbModePermission;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.util.Function;
import com.jetbrains.edu.learning.EduPluginConfigurator;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.StudyUtils;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.StudyItem;
import com.jetbrains.edu.learning.courseFormat.TaskFile;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CCUtils {
  public static final String ANSWER_EXTENSION_DOTTED = ".answer.";
  private static final Logger LOG = Logger.getInstance(CCUtils.class);
  public static final String GENERATED_FILES_FOLDER = ".coursecreator";
  public static final String COURSE_MODE = "Course Creator";

  public static int getSubtaskIndex(@NotNull Project project, @NotNull VirtualFile file) {
    String fileName = file.getName();
    String name = FileUtil.getNameWithoutExtension(fileName);
    boolean canBeSubtaskFile = isTestsFile(project, file) || StudyUtils.isTaskDescriptionFile(fileName);
    if (!canBeSubtaskFile) {
      return -1;
    }
    if (!name.contains(EduNames.SUBTASK_MARKER)) {
      return 0;
    }
    int markerIndex = name.indexOf(EduNames.SUBTASK_MARKER);
    String index = name.substring(markerIndex + EduNames.SUBTASK_MARKER.length());
    if (index.isEmpty()) {
      return -1;
    }
    try {
      return Integer.valueOf(index);
    }
    catch (NumberFormatException e) {
      return -1;
    }
  }

  /**
   * This method decreases index and updates directory names of
   * all tasks/lessons that have higher index than specified object
   *
   * @param dirs         directories that are used to get tasks/lessons
   * @param getStudyItem function that is used to get task/lesson from VirtualFile. This function can return null
   * @param threshold    index is used as threshold
   * @param prefix       task or lesson directory name prefix
   */
  public static void updateHigherElements(VirtualFile[] dirs,
                                          @NotNull final Function<VirtualFile, ? extends StudyItem> getStudyItem,
                                          final int threshold,
                                          final String prefix,
                                          final int delta) {
    ArrayList<VirtualFile> dirsToRename = new ArrayList<>
      (Collections2.filter(Arrays.asList(dirs), new Predicate<VirtualFile>() {
        @Override
        public boolean apply(VirtualFile dir) {
          final StudyItem item = getStudyItem.fun(dir);
          if (item == null) {
            return false;
          }
          int index = item.getIndex();
          return index > threshold;
        }
      }));
    Collections.sort(dirsToRename, (o1, o2) -> {
      StudyItem item1 = getStudyItem.fun(o1);
      StudyItem item2 = getStudyItem.fun(o2);
      //if we delete some dir we should start increasing numbers in dir names from the end
      return (-delta) * EduUtils.INDEX_COMPARATOR.compare(item1, item2);
    });

    for (final VirtualFile dir : dirsToRename) {
      final StudyItem item = getStudyItem.fun(dir);
      final int newIndex = item.getIndex() + delta;
      item.setIndex(newIndex);
      ApplicationManager.getApplication().runWriteAction(new Runnable() {
        @Override
        public void run() {
          try {
            dir.rename(this, prefix + newIndex);
          }
          catch (IOException e) {
            LOG.error(e);
          }
        }
      });
    }
  }

  public static boolean isLessonDir(PsiDirectory sourceDirectory) {
    if (sourceDirectory == null) {
      return false;
    }
    Project project = sourceDirectory.getProject();
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course != null && isCourseCreator(project) && course.getLesson(sourceDirectory.getName()) != null) {
      return true;
    }
    return false;
  }


  public static VirtualFile getGeneratedFilesFolder(@NotNull Project project, @NotNull Module module) {
    VirtualFile baseDir = project.getBaseDir();
    VirtualFile folder = baseDir.findChild(GENERATED_FILES_FOLDER);
    if (folder != null) {
      return folder;
    }
    final Ref<VirtualFile> generatedRoot = new Ref<>();
    DumbService.allowStartingDumbModeInside(DumbModePermission.MAY_START_BACKGROUND, new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runWriteAction(new Runnable() {
          @Override
          public void run() {
            try {
              generatedRoot.set(baseDir.createChildDirectory(this, GENERATED_FILES_FOLDER));
              VirtualFile contentRootForFile =
                ProjectRootManager.getInstance(module.getProject()).getFileIndex().getContentRootForFile(generatedRoot.get());
              if (contentRootForFile == null) {
                return;
              }
              ModuleRootModificationUtil.updateExcludedFolders(module, contentRootForFile, Collections.emptyList(),
                                                               Collections.singletonList(generatedRoot.get().getUrl()));
            }
            catch (IOException e) {
              LOG.info("Failed to create folder for generated files", e);
            }
          }
        });
      }
    });
    return generatedRoot.get();
  }

  @Nullable
  public static VirtualFile generateFolder(@NotNull Project project, @NotNull Module module, String name) {
    VirtualFile generatedRoot = getGeneratedFilesFolder(project, module);
    if (generatedRoot == null) {
      return null;
    }

    final Ref<VirtualFile> folder = new Ref<>(generatedRoot.findChild(name));
    //need to delete old folder
    ApplicationManager.getApplication().runWriteAction(() -> {
      try {
        if (folder.get() != null) {
          folder.get().delete(null);
        }
        folder.set(generatedRoot.createChildDirectory(null, name));
      }
      catch (IOException e) {
        LOG.info("Failed to generate folder " + name, e);
      }
    });
    return folder.get();
  }

  public static boolean isCourseCreator(@NotNull Project project) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return false;
    }

    return COURSE_MODE.equals(course.getCourseMode());
  }

  public static boolean isTestsFile(@NotNull Project project, @NotNull VirtualFile file) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return false;
    }
    EduPluginConfigurator configurator = EduPluginConfigurator.INSTANCE.forLanguage(course.getLanguageById());
    if (configurator == null) {
      return false;
    }
    return configurator.isTestFile(file);
  }

  public static void createResourceFile(VirtualFile createdFile, Course course, VirtualFile taskVF) {
    VirtualFile lessonVF = taskVF.getParent();
    if (lessonVF == null) {
      return;
    }

    String taskResourcesPath = FileUtil.join(course.getCourseDirectory(), lessonVF.getName(), taskVF.getName());
    File taskResourceFile = new File(taskResourcesPath);
    if (!taskResourceFile.exists()) {
      if (!taskResourceFile.mkdirs()) {
        LOG.info("Failed to create resources for task " + taskResourcesPath);
      }
    }
    try {
      File toFile = new File(taskResourceFile, createdFile.getName());
      FileUtil.copy(new File(createdFile.getPath()), toFile);
    }
    catch (IOException e) {
      LOG.info("Failed to copy created task file to resources " + createdFile.getPath());
    }
  }


  public static void updateResources(Project project, Task task, VirtualFile taskDir) {
    Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) {
      return;
    }
    VirtualFile lessonVF = taskDir.getParent();
    if (lessonVF == null) {
      return;
    }

    String taskResourcesPath = FileUtil.join(course.getCourseDirectory(), lessonVF.getName(), taskDir.getName());
    File taskResourceFile = new File(taskResourcesPath);
    if (!taskResourceFile.exists()) {
      if (!taskResourceFile.mkdirs()) {
        LOG.info("Failed to create resources for task " + taskResourcesPath);
      }
    }
    VirtualFile studentDir = LocalFileSystem.getInstance().findFileByIoFile(taskResourceFile);
    if (studentDir == null) {
      return;
    }
    for (Map.Entry<String, TaskFile> entry : task.getTaskFiles().entrySet()) {
      String name = entry.getKey();
      VirtualFile answerFile = taskDir.findFileByRelativePath(name);
      if (answerFile == null) {
        continue;
      }
      ApplicationManager.getApplication().runWriteAction(() -> {
        EduUtils.createStudentFile(CCUtils.class, project, answerFile, studentDir, null,
                                   task instanceof TaskWithSubtasks ? ((TaskWithSubtasks)task).getActiveSubtaskIndex() : 0);
      });
    }
  }

  public static void updateActionGroup(AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getProject();
    presentation.setEnabledAndVisible(project != null && isCourseCreator(project));
  }

  /**
   * @param fromIndex -1 if task converted to TaskWithSubtasks, -2 if task converted from TaskWithSubtasks
   */
  public static void renameFiles(VirtualFile taskDir, Project project, int fromIndex) {
    ApplicationManager.getApplication().runWriteAction(() -> {
      Map<VirtualFile, String> newNames = new HashMap<>();
      for (VirtualFile virtualFile : taskDir.getChildren()) {
        int subtaskIndex = getSubtaskIndex(project, virtualFile);
        if (subtaskIndex == -1) {
          continue;
        }
        if (subtaskIndex > fromIndex) {
          String index;
          if (fromIndex == -1) { // add new subtask
            index = "0";
          }
          else { // remove subtask
            index = fromIndex == -2 ? "" : Integer.toString(subtaskIndex - 1);
          }
          String fileName = virtualFile.getName();
          String nameWithoutExtension = FileUtil.getNameWithoutExtension(fileName);
          String extension = FileUtilRt.getExtension(fileName);
          int subtaskMarkerIndex = nameWithoutExtension.indexOf(EduNames.SUBTASK_MARKER);
          String newName = subtaskMarkerIndex == -1
                           ? nameWithoutExtension
                           : nameWithoutExtension.substring(0, subtaskMarkerIndex);
          newName += index.isEmpty() ? "" : EduNames.SUBTASK_MARKER;
          newName += index + "." + extension;
          newNames.put(virtualFile, newName);
        }
      }
      for (Map.Entry<VirtualFile, String> entry : newNames.entrySet()) {
        try {
          entry.getKey().rename(project, entry.getValue());
        }
        catch (IOException e) {
          LOG.info(e);
        }
      }
    });
  }
}
