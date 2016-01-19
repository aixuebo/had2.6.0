/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.fs;

import java.io.File;
import org.apache.hadoop.util.Shell;

public class DUHelper {

  private int folderCount=0;//文件包含的目录个数
  private int fileCount=0;//文件的个数
  private double usage = 0;//计算使用的磁盘占该磁盘的百分比
  private long folderSize = -1;//文件大小或者目录下所有文件的大小

  private DUHelper() {

  }

  public static long getFolderUsage(String folder) {
    return new DUHelper().calculateFolderSize(folder);
  }

  /**
   * 计算该文件或者目录的大小
   */
  private long calculateFolderSize(String folder) {
    if (folder == null)
      throw new IllegalArgumentException("folder");
    File f = new File(folder);
    return folderSize = getFileSize(f);
  }

  public String check(String folder) {
    if (folder == null)
      throw new IllegalArgumentException("folder");
    File f = new File(folder);

    //该文件或者目录的大小
    folderSize = getFileSize(f);
    //计算使用了占磁盘的百分比
    usage = 1.0*(f.getTotalSpace() - f.getFreeSpace())/ f.getTotalSpace();
    //使用多少磁盘数量,其中文件数量是多少，占上的百分比
    return String.format("used %d files %d disk in use %f", folderSize, fileCount, usage);
  }

  public long getFileCount() {
    return fileCount;
  }

  public double getUsage() {
    return usage;
  }

  /**
   * 计算文件大小
   */
  private long getFileSize(File folder) {

    folderCount++;//
    //Counting the total folders
    long foldersize = 0;
    //是文件
    if (folder.isFile())
      return folder.length();
    
    //是目录
    File[] filelist = folder.listFiles();
    if (filelist == null) {
      return 0;
    }
    for (int i = 0; i < filelist.length; i++) {
      if (filelist[i].isDirectory()) {//是目录
        foldersize += getFileSize(filelist[i]);//累加该目录的大小
      } else {//是文件
        fileCount++; //Counting the total files
        foldersize += filelist[i].length();//累加该文件的大小
      }
    }
    return foldersize;    
  }

  public static void main(String[] args) {
    if (Shell.WINDOWS)
      System.out.println("Windows: "+ DUHelper.getFolderUsage(args[0]));
    else
      System.out.println("Other: " + DUHelper.getFolderUsage(args[0]));
  }
}