package com.jetbrains.edu.coursecreator.actions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.ide.projectView.ProjectView;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.io.ZipUtil;
import com.jetbrains.edu.coursecreator.CCUtils;
import com.jetbrains.edu.coursecreator.ui.CreateCourseArchiveDialog;
import com.jetbrains.edu.learning.StudySerializationUtils;
import com.jetbrains.edu.learning.EduPluginConfigurator;
import com.jetbrains.edu.learning.StudyTaskManager;
import com.jetbrains.edu.learning.core.EduNames;
import com.jetbrains.edu.learning.core.EduUtils;
import com.jetbrains.edu.learning.courseFormat.Course;
import com.jetbrains.edu.learning.courseFormat.Lesson;
import com.jetbrains.edu.learning.courseFormat.tasks.Task;
import com.jetbrains.edu.learning.courseFormat.tasks.TaskWithSubtasks;
import com.jetbrains.edu.learning.statistics.EduUsagesCollector;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipOutputStream;

public class CCCreateCourseArchive extends DumbAwareAction {
  private static final Logger LOG = Logger.getInstance(CCCreateCourseArchive.class.getName());
  public static final String GENERATE_COURSE_ARCHIVE = "Generate Course Archive";
  private String myZipName;
  private String myLocationDir;

  public void setZipName(String zipName) {
    myZipName = zipName;
  }

  public void setLocationDir(String locationDir) {
    myLocationDir = locationDir;
  }

  public CCCreateCourseArchive() {
    super(GENERATE_COURSE_ARCHIVE, GENERATE_COURSE_ARCHIVE, null);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    Presentation presentation = e.getPresentation();
    Project project = e.getProject();
    presentation.setEnabledAndVisible(project != null && CCUtils.isCourseCreator(project));
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    final Project project = e.getData(CommonDataKeys.PROJECT);
    final Module module = e.getData(LangDataKeys.MODULE);
    if (project == null || module == null) {
      return;
    }
    CreateCourseArchiveDialog dlg = new CreateCourseArchiveDialog(project, this);
    dlg.show();
    if (dlg.getExitCode() != DialogWrapper.OK_EXIT_CODE) {
      return;
    }
    createCourseArchive(project, module, myZipName, myLocationDir, true);
    EduUsagesCollector.createdCourseArchive();
  }

  public static void createCourseArchive(final Project project, Module module, String zipName, String locationDir, boolean showMessage) {
    final Course course = StudyTaskManager.getInstance(project).getCourse();
    if (course == null) return;
    final VirtualFile baseDir = project.getBaseDir();
    VirtualFile archiveFolder = CCUtils.generateFolder(project, module, zipName);
    if (archiveFolder == null) {
      return;
    }

    EduPluginConfigurator configurator = EduPluginConfigurator.INSTANCE.forLanguage(course.getLanguageById());
    if (configurator == null) {
      return;
    }
    FileFilter filter = pathname -> !configurator.excludeFromArchive(pathname);

    for (VirtualFile child : baseDir.getChildren()) {
      String name = child.getName();
      File fromFile = new File(child.getPath());
      if (CCUtils.GENERATED_FILES_FOLDER.equals(name) || Project.DIRECTORY_STORE_FOLDER.equals(name)
          || name.contains("iml") || configurator.excludeFromArchive(fromFile)) {
        continue;
      }
      copyChild(archiveFolder, filter, child, fromFile);
    }

    ApplicationManager.getApplication().runWriteAction(new Runnable() {
      @Override
      public void run() {
        archiveFolder.refresh(false, true);
        Course courseCopy = course.copy();
        replaceAnswerFilesWithTaskFiles(courseCopy);
        generateJson(archiveFolder, courseCopy);
        VirtualFileManager.getInstance().refreshWithoutFileWatcher(false);
        packCourse(archiveFolder, locationDir, zipName, showMessage);
        synchronize(project);
      }

      private void replaceAnswerFilesWithTaskFiles(Course courseCopy) {
        for (Lesson lesson : courseCopy.getLessons()) {
          String lessonDirName = EduNames.LESSON + String.valueOf(lesson.getIndex());
          final VirtualFile lessonDir = baseDir.findChild(lessonDirName);
          if (lessonDir == null) continue;
          for (Task task : lesson.getTaskList()) {
            final VirtualFile taskDir = task.getTaskDir(project);
            if (taskDir == null) continue;
            String taskDirName = EduNames.TASK + String.valueOf(task.getIndex());
            VirtualFile studentFileDir = VfsUtil.findRelativeFile(archiveFolder, lessonDirName, taskDirName);
            if (studentFileDir == null) {
              continue;
            }
            VirtualFile srcDir = studentFileDir.findChild(EduNames.SRC);
            if (srcDir != null) {
              studentFileDir = srcDir;
            }
            if (task instanceof TaskWithSubtasks) {
              transformSubtaskTestsToTextFiles(studentFileDir);
            }
            for (String taskFile : task.getTaskFiles().keySet()) {
              VirtualFile answerFile = taskDir.findFileByRelativePath(taskFile);
              if (answerFile == null) {
                continue;
              }
              EduUtils.createStudentFile(this, project, answerFile, studentFileDir, task, 0);
            }
          }
        }
      }

      private void transformSubtaskTestsToTextFiles(VirtualFile studentFileDir) {
        Condition<VirtualFile> isSubtaskTestFile =
          file -> CCUtils.isTestsFile(project, file) && file.getName().contains(EduNames.SUBTASK_MARKER);
        List<VirtualFile> subtaskTests = ContainerUtil.filter(Arrays.asList(studentFileDir.getChildren()), isSubtaskTestFile);
        for (VirtualFile subtaskTest : subtaskTests) {
          try {
            subtaskTest.rename(this, subtaskTest.getNameWithoutExtension() + ".txt");
          }
          catch (IOException e) {
            LOG.error(e);
          }
        }
      }
    });
  }

  private static void copyChild(VirtualFile archiveFolder, FileFilter filter, VirtualFile child, File fromFile) {
    File toFile = new File(archiveFolder.getPath(), child.getName());

    try {
      if (child.isDirectory()) {
        FileUtil.copyDir(fromFile, toFile, filter);
      }
      else {
        if (filter.accept(fromFile)) {
          FileUtil.copy(fromFile, toFile);
        }
      }
    }
    catch (IOException e) {
      LOG.info("Failed to copy" + fromFile.getPath(), e);
    }
  }

  private static void synchronize(@NotNull final Project project) {
    VirtualFileManager.getInstance().refreshWithoutFileWatcher(true);
    ProjectView.getInstance(project).refresh();
  }

  private static void packCourse(@NotNull final VirtualFile baseDir, String locationDir, String zipName, boolean showMessage) {
    try {
      final File zipFile = new File(locationDir, zipName + ".zip");
      ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
      VirtualFile[] courseFiles = baseDir.getChildren();
      for (VirtualFile file : courseFiles) {
        ZipUtil.addFileOrDirRecursively(zos, null, new File(file.getPath()), file.getName(), null, null);
      }
      zos.close();
      if (showMessage) {
        ApplicationManager.getApplication().invokeLater(
          () -> Messages.showInfoMessage("Course archive was saved to " + zipFile.getPath(),
                                         "Course Archive Was Created Successfully"));

      }
    }
    catch (IOException e1) {
      LOG.error(e1);
    }
  }

  @SuppressWarnings("IOResourceOpenedButNotSafelyClosed")
  private static void generateJson(VirtualFile parentDir, Course course) {
    final Gson gson = new GsonBuilder().setPrettyPrinting().excludeFieldsWithoutExposeAnnotation().
      registerTypeAdapter(Task.class, new StudySerializationUtils.Json.TaskSerializer()).create();
    final String json = gson.toJson(course);
    final File courseJson = new File(parentDir.getPath(), EduNames.COURSE_META_FILE);
    OutputStreamWriter outputStreamWriter = null;
    try {
      outputStreamWriter = new OutputStreamWriter(new FileOutputStream(courseJson), "UTF-8");
      outputStreamWriter.write(json);
    }
    catch (Exception e) {
      Messages.showErrorDialog(e.getMessage(), "Failed to Generate Json");
      LOG.info(e);
    }
    finally {
      try {
        if (outputStreamWriter != null) {
          outputStreamWriter.close();
        }
      }
      catch (IOException e1) {
        //close silently
      }
    }
  }
}