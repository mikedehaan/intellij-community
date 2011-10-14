/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.core;

import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.impl.jar.CoreJarFileSystem;
import com.intellij.psi.*;
import com.intellij.psi.impl.file.PsiPackageImpl;
import com.intellij.psi.impl.file.impl.JavaFileManager;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author yole
 */
public class CoreJavaFileManager implements JavaFileManager {
  private final CoreJarFileSystem myJarFileSystem;
  private final List<File> myClasspath = new ArrayList<File>();
  private final PsiManager myPsiManager;

  public CoreJavaFileManager(PsiManager psiManager, CoreJarFileSystem jarFileSystem) {
    myPsiManager = psiManager;
    myJarFileSystem = jarFileSystem;
  }

  @Override
  public PsiPackage findPackage(@NotNull String packageName) {
    String dirName = packageName.replace(".", "/");
    for (File file : myClasspath) {
      if (file.isFile()) {
        VirtualFile classDir = myJarFileSystem.findFileByPath(file.getPath() + "!/" + dirName);
        if (classDir != null) {
          return new PsiPackageImpl(myPsiManager, packageName);
        }
      }
    }
    return null;
  }

  @Override
  public PsiClass findClass(@NotNull String qName, @NotNull GlobalSearchScope scope) {
    for (File file : myClasspath) {
      final PsiClass psiClass = findClassInClasspathEntry(qName, file);
      if (psiClass != null) {
        return null;
      }
    }
    return null;
  }

  @Nullable
  private PsiClass findClassInClasspathEntry(String qName, File file) {
    String fileName = qName.replace(".", "/") + ".class";
    if (file.isFile()) {
      VirtualFile classFile = myJarFileSystem.findFileByPath(file.getPath() + "!/" + fileName);
      if (classFile != null) {
        PsiFile psiFile = myPsiManager.findFile(classFile);
        if (!(psiFile instanceof PsiJavaFile)) {
          throw new UnsupportedOperationException("no java file for .class");
        }
        final PsiClass[] classes = ((PsiJavaFile)psiFile).getClasses();
        if (classes.length == 1) {
          return classes[0];
        }
      }
    }
    return null;
  }

  @Override
  public PsiClass[] findClasses(@NotNull String qName, @NotNull GlobalSearchScope scope) {
    List<PsiClass> result = new ArrayList<PsiClass>();
    for (File file : myClasspath) {
      final PsiClass psiClass = findClassInClasspathEntry(qName, file);
      if (psiClass != null) {
        result.add(psiClass);
      }
    }
    return result.toArray(new PsiClass[result.size()]);
  }

  @Override
  public Collection<String> getNonTrivialPackagePrefixes() {
    return Collections.emptyList();
  }

  @Override
  public void initialize() {
  }

  public void addToClasspath(File path) {
    myClasspath.add(path);
  }
}
