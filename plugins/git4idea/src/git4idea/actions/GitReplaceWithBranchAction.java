/*
 * Copyright 2000-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package git4idea.actions;

import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.components.JBList;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.ui.UIUtil;
import com.intellij.vcsUtil.VcsFileUtil;
import com.intellij.vcsUtil.VcsUtil;
import git4idea.GitBranch;
import git4idea.GitRevisionNumber;
import git4idea.GitUtil;
import git4idea.GitVcs;
import git4idea.checkout.GitCheckoutProvider;
import git4idea.commands.Git;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;
import git4idea.util.GitUIUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Replaces the selected file with itself from another branch.
 *
 * @author Mike De Haan
 */
public class GitReplaceWithBranchAction extends DumbAwareAction {

  private static final Logger LOG = Logger.getInstance(GitReplaceWithBranchAction.class.getName());

  @Override
  public void actionPerformed(@NotNull AnActionEvent event) {
    Project project = event.getRequiredData(CommonDataKeys.PROJECT);
    VirtualFile file = getAffectedFile(event);
    GitRepository repository = GitUtil.getRepositoryManager(project).getRepositoryForFile(file);
    assert repository != null;

    // Retrieve the list of branch names to show in the pop up list
    List<String> branchNames = getBranchNames(repository);

    // Create a pop up of branch names for the user to choose
    JBList list = new JBList(branchNames);
    JBPopupFactory.getInstance().createListPopupBuilder(list).setTitle("Select branch to replace from")
      .setItemChoosenCallback(new OnBranchChooseRunnable(project, file, list)).setAutoselectOnMouseMove(true)
      .setFilteringEnabled(new Function<Object, String>() {
        @Override
        public String fun(Object o) {
          return o.toString();
        }
      }).createPopup().showInBestPositionFor(event.getDataContext());
  }

  /**
   * Retrieve the list of branch names available for the user to choose from.
   * Branch names diaplayed with local branches first followed by remote, with each list sorted alphabetically.
   *
   * @param repository
   * @return
   */
  @NotNull
  private static List<String> getBranchNames(@NotNull GitRepository repository) {
    List<GitBranch> localBranches = new ArrayList<GitBranch>(repository.getBranches().getLocalBranches());
    Collections.sort(localBranches);
    List<GitBranch> remoteBranches = new ArrayList<GitBranch>(repository.getBranches().getRemoteBranches());
    Collections.sort(remoteBranches);

    List<String> branchNames = ContainerUtil.newArrayList();
    for (GitBranch branch : localBranches) {
      branchNames.add(branch.getName());
    }
    for (GitBranch branch : remoteBranches) {
      branchNames.add(branch.getName());
    }
    return branchNames;
  }

  /**
   * Retrieve the file the user has selected to be replaced.
   *
   * @param event
   * @return The selected file
   */
  private static VirtualFile getAffectedFile(@NotNull AnActionEvent event) {
    final VirtualFile[] vFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    assert vFiles != null && vFiles.length == 1 && vFiles[0] != null : "Illegal virtual files selected: " + Arrays.toString(vFiles);
    return vFiles[0];
  }

  /**
   * Hide/Show the action based on the context within the project.
   *
   * @param e Carries information on the invocation place and data available
   */
  @Override
  public void update(@NotNull AnActionEvent e) {
    super.update(e);
    Presentation presentation = e.getPresentation();
    Project project = e.getProject();
    if (project == null) {
      presentation.setEnabled(false);
      presentation.setVisible(false);
      return;
    }

    VirtualFile[] vFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY);
    if (vFiles == null || vFiles.length != 1 || vFiles[0] == null) { // only 1 file for now
      presentation.setEnabled(false);
      presentation.setVisible(true);
      return;
    }

    GitRepositoryManager manager = GitUtil.getRepositoryManager(project);

    GitRepository repository = manager.getRepositoryForFile(vFiles[0]);
    if (repository == null || repository.isFresh() || noBranchesToUse(repository)) {
      presentation.setEnabled(false);
      presentation.setVisible(true);
      return;
    }

    presentation.setEnabled(true);
    presentation.setVisible(true);
  }

  private static boolean noBranchesToUse(@NotNull GitRepository repository) {
    int locals = repository.getBranches().getLocalBranches().size();
    boolean haveRemotes = !repository.getBranches().getRemoteBranches().isEmpty();
    if (repository.isOnBranch()) {  // there are other branches to compare
      return locals < 2 && !haveRemotes;
    }
    return locals == 0 && !haveRemotes; // there are at least 1 branch to compare
  }

  private static class OnBranchChooseRunnable implements Runnable {
    private final Project myProject;
    private final VirtualFile myFile;
    private final JList myList;

    OnBranchChooseRunnable(@NotNull Project project, @NotNull VirtualFile file, @NotNull JList list) {
      myProject = project;
      myFile = file;
      myList = list;
    }

    @Override
    public void run() {
      Object selectedValue = myList.getSelectedValue();
      if (selectedValue == null) {
        LOG.error("Selected value is unexpectedly null");
        return;
      }
      String fromBranch = selectedValue.toString();
      try {

        FilePath filePath = VcsUtil.getFilePath(myFile);
        // we could use something like GitRepository#getCurrentRevision here,
        // but this way we can easily identify if the file is available in the branch
        GitRevisionNumber compareRevisionNumber =
          (GitRevisionNumber)GitHistoryUtils.getCurrentRevision(myProject, filePath, fromBranch);
        if (compareRevisionNumber == null) {
          fileDoesntExistInBranchError(myProject, myFile, fromBranch);
          return;
        }

        // Retrieve the associated git repository
        GitRepository repository = GitUtil.getRepositoryManager(myProject).getRepositoryForFile(myFile);

        // Convert the absolute path to a relative path for Git
        String fileRelativePath = VcsFileUtil.relativePath(repository.getRoot(), myFile);

        // Execute the git command to checkout the appropriate file
        GitCheckoutProvider.checkoutFile(myProject, ServiceManager.getService(Git.class), repository, fileRelativePath, fromBranch);

        repository.update();

        final VirtualFile[] affectedFiles = new VirtualFile[] {myFile};
        final List<VcsException> exceptions = new ArrayList<VcsException>();
        final String actionName = "replaceWithBranch";

        // Refresh the editor with the new revision
        GitVcs.runInBackground(new Task.Backgroundable(myProject, actionName) {
          public void run(@NotNull ProgressIndicator indicator) {
            VfsUtil.markDirtyAndRefresh(false, true, false, affectedFiles);
            VcsFileUtil.markFilesDirty(myProject, Arrays.asList(affectedFiles));
            UIUtil.invokeLaterIfNeeded(new Runnable() {
              public void run() {
                GitUIUtil.showOperationErrors(myProject, exceptions, actionName);
              }
            });
          }
        });
      }
      catch (VcsException e) {
        if (e.getMessage().contains("exists on disk, but not in")) {
          fileDoesntExistInBranchError(myProject, myFile, fromBranch);
        }
        else {
          GitUIUtil.notifyError(myProject, "Couldn't replace with branch version",
                                String.format("Couldn't replace file [%s] with selected branch [%s]", myFile, selectedValue), false, e);
        }
      }
    }

    private static void fileDoesntExistInBranchError(@NotNull Project project, @NotNull VirtualFile file, @NotNull String branchToCompare) {
      GitUIUtil.notifyMessage(project, GitUtil.fileOrFolder(file) + " doesn't exist in branch", String
        .format("%s <code>%s</code> doesn't exist in branch <code>%s</code>", GitUtil.fileOrFolder(file), file.getPresentableUrl(),
                branchToCompare), false, null);
    }
  }
}
