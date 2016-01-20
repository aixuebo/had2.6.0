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

import java.io.*;
import java.util.*;

import org.apache.commons.logging.*;

import org.apache.hadoop.util.*;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.DiskChecker.DiskErrorException;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration; 

/** An implementation of a round-robin scheme for disk allocation for creating
 * files. The way it works is that it is kept track what disk was last
 * allocated for a file write. For the current request, the next disk from
 * the set of disks would be allocated if the free space on the disk is 
 * sufficient enough to accommodate the file that is being considered for
 * creation. If the space requirements cannot be met, the next disk in order
 * would be tried and so on till a disk is found with sufficient capacity.
 * Once a disk with sufficient space is identified, a check is done to make
 * sure that the disk is writable. Also, there is an API provided that doesn't
 * take the space requirements into consideration but just checks whether the
 * disk under consideration is writable (this should be used for cases where
 * the file size is not known apriori). An API is provided to read a path that
 * was created earlier. That API works by doing a scan of all the disks for the
 * input pathname.
 * This implementation also provides the functionality of having multiple 
 * allocators per JVM (one for each unique functionality or context, like 
 * mapred, dfs-client, etc.). It ensures that there is only one instance of
 * an allocator per context per JVM.
 * Note:
 * 1. The contexts referred above are actually the configuration items defined
 * in the Configuration class like "mapred.local.dir" (for which we want to 
 * control the dir allocations). The context-strings are exactly those 
 * configuration items.
 * 2. This implementation does not take into consideration cases where
 * a disk becomes read-only or goes out of space while a file is being written
 * to (disks are shared between multiple processes, and so the latter situation
 * is probable).
 * 3. In the class implementation, "Disk" is referred to as "Dir", which
 * actually points to the configured directory on the Disk which will be the
 * parent for all file write/read allocations.
 * 
 * 多个文件路径,应该以什么规则进行遍历使用每一个路径的解决方案
 * 一个NodeManager对应一个该对象
 */
@InterfaceAudience.LimitedPrivate({"HDFS", "MapReduce"})
@InterfaceStability.Unstable
public class LocalDirAllocator {
  
  //A Map from the config item names like "mapred.local.dir"
  //to the instance of the AllocatorPerContext. This
  //is a static object to make sure there exists exactly one instance per JVM
  private static Map <String, AllocatorPerContext> contexts = 
                 new TreeMap<String, AllocatorPerContext>();
  private String contextCfgItemName;//yarn.nodemanager.log-dirs 或者yarn.nodemanager.local-dirs

  /** Used when size of file to be allocated is unknown. */
  public static final int SIZE_UNKNOWN = -1;

  /**Create an allocator object
   * @param contextCfgItemName,yarn.nodemanager.log-dirs 或者yarn.nodemanager.local-dirs,表示该资源分配器是针对数据目录的还是NodeManager的日志目录操作的
   */
  public LocalDirAllocator(String contextCfgItemName) {
    this.contextCfgItemName = contextCfgItemName;
  }
  
  /** This method must be used to obtain the dir allocation context for a 
   * particular value of the context name. The context name must be an item
   * defined in the Configuration object for which we want to control the 
   * dir allocations (e.g., <code>mapred.local.dir</code>). The method will
   * create a context for that name if it doesn't already exist.
   */
  private AllocatorPerContext obtainContext(String contextCfgItemName) {
    synchronized (contexts) {
      AllocatorPerContext l = contexts.get(contextCfgItemName);
      if (l == null) {
        contexts.put(contextCfgItemName, 
                    (l = new AllocatorPerContext(contextCfgItemName)));
      }
      return l;
    }
  }
  
  /** Get a path from the local FS. This method should be used if the size of 
   *  the file is not known apriori. We go round-robin over the set of disks
   *  (via the configured dirs) and return the first complete path where
   *  we could create the parent directory of the passed path. 
   *  @param pathStr the requested path (this will be created on the first 
   *  available disk)
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   *  在多个目录中找到一个可以写的目录,目录名称为pathStr
   */
  public Path getLocalPathForWrite(String pathStr, 
      Configuration conf) throws IOException {
    return getLocalPathForWrite(pathStr, SIZE_UNKNOWN, conf);
  }
  
  /** Get a path from the local FS. Pass size as 
   *  SIZE_UNKNOWN if not known apriori. We
   *  round-robin over the set of disks (via the configured dirs) and return
   *  the first complete path which has enough space 
   *  @param pathStr the requested path (this will be created on the first 
   *  available disk)
   *  @param size the size of the file that is going to be written
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   *  在多个目录中找到一个可以写size大小的目录,目录名称为pathStr
   */
  public Path getLocalPathForWrite(String pathStr, long size, 
      Configuration conf) throws IOException {
    return getLocalPathForWrite(pathStr, size, conf, true);
  }
  
  /** Get a path from the local FS. Pass size as 
   *  SIZE_UNKNOWN if not known apriori. We
   *  round-robin over the set of disks (via the configured dirs) and return
   *  the first complete path which has enough space 
   *  @param pathStr the requested path (this will be created on the first 
   *  available disk)
   *  @param size the size of the file that is going to be written
   *  @param conf the Configuration object
   *  @param checkWrite ensure that the path is writable
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   *  在多个目录中找到一个可以写size大小的目录,目录名称为pathStr
   */
  public Path getLocalPathForWrite(String pathStr, long size, 
                                   Configuration conf,
                                   boolean checkWrite) throws IOException {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.getLocalPathForWrite(pathStr, size, conf, checkWrite);
  }
  
  /** Get a path from the local FS for reading. We search through all the
   *  configured dirs for the file's existence and return the complete
   *  path to the file when we find one 
   *  @param pathStr the requested file (this will be searched)
   *  @param conf the Configuration object
   *  @return the complete path to the file on a local disk
   *  @throws IOException
   *  在多个目录中找到第一个有pathStr的目录
   */
  public Path getLocalPathToRead(String pathStr, 
      Configuration conf) throws IOException {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.getLocalPathToRead(pathStr, conf);
  }
  
  /**
   * Get all of the paths that currently exist in the working directories.
   * @param pathStr the path underneath the roots
   * @param conf the configuration to look up the roots in
   * @return all of the paths that exist under any of the roots
   * @throws IOException
   * 在多个目录中找到所有pathStr的目录
   */
  public Iterable<Path> getAllLocalPathsToRead(String pathStr, 
                                               Configuration conf
                                               ) throws IOException {
    AllocatorPerContext context;
    synchronized (this) {
      context = obtainContext(contextCfgItemName);
    }
    return context.getAllLocalPathsToRead(pathStr, conf);    
  }

  /** Creates a temporary file in the local FS. Pass size as -1 if not known 
   *  apriori. We round-robin over the set of disks (via the configured dirs) 
   *  and select the first complete path which has enough space. A file is
   *  created on this directory. The file is guaranteed to go away when the
   *  JVM exits.
   *  @param pathStr prefix for the temporary file
   *  @param size the size of the file that is going to be written
   *  @param conf the Configuration object
   *  @return a unique temporary file
   *  @throws IOException
   *  建立临时文件,jvm关闭的时候,则文件被删除
   */
  public File createTmpFileForWrite(String pathStr, long size, 
      Configuration conf) throws IOException {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.createTmpFileForWrite(pathStr, size, conf);
  }
  
  /** Method to check whether a context is valid
   * @param contextCfgItemName
   * @return true/false
   */
  public static boolean isContextValid(String contextCfgItemName) {
    synchronized (contexts) {
      return contexts.containsKey(contextCfgItemName);
    }
  }
  
  /**
   * Removes the context from the context config items
   * 
   * @param contextCfgItemName
   */
  @Deprecated
  @InterfaceAudience.LimitedPrivate({"MapReduce"})
  public static void removeContext(String contextCfgItemName) {
    synchronized (contexts) {
      contexts.remove(contextCfgItemName);
    }
  }
    
  /** We search through all the configured dirs for the file's existence
   *  and return true when we find  
   *  @param pathStr the requested file (this will be searched)
   *  @param conf the Configuration object
   *  @return true if files exist. false otherwise
   *  @throws IOException
   *  是否存在该pathStr目录
   */
  public boolean ifExists(String pathStr,Configuration conf) {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.ifExists(pathStr, conf);
  }

  /**
   * Get the current directory index for the given configuration item.
   * @return the current directory index for the given configuration item.
   * 获取当前已经到第几个目录下了
   */
  int getCurrentDirectoryIndex() {
    AllocatorPerContext context = obtainContext(contextCfgItemName);
    return context.getCurrentDirectoryIndex();
  }
  
  private static class AllocatorPerContext {

    private final Log LOG =
      LogFactory.getLog(AllocatorPerContext.class);

    private int dirNumLastAccessed;//获取当前应该循环第几个目录了
    private Random dirIndexRandomizer = new Random();
    private FileSystem localFS;//本地文件操作系统
    private String contextCfgItemName;//yarn.nodemanager.log-dirs 或者yarn.nodemanager.local-dirs
    private String[] localDirs;//最新的可用的目录集合
    private DF[] dirDF;//最新的可用的目录集合,每一个目录对应一个DF对象
    private String savedLocalDirs = "";

    public AllocatorPerContext(String contextCfgItemName) {
      this.contextCfgItemName = contextCfgItemName;
    }

    /** This method gets called everytime before any read/write to make sure
     * that any change to localDirs is reflected immediately.
     */
    private synchronized void confChanged(Configuration conf) 
        throws IOException {
      String newLocalDirs = conf.get(contextCfgItemName);//获取对应的目录集合
      if (!newLocalDirs.equals(savedLocalDirs)) {//判断上一次和这次的目录集合是否不同,不同的原因是因为有些目录损坏了.因此被干掉了
        localDirs = StringUtils.getTrimmedStrings(newLocalDirs);
        localFS = FileSystem.getLocal(conf);
        int numDirs = localDirs.length;
        ArrayList<String> dirs = new ArrayList<String>(numDirs);
        ArrayList<DF> dfList = new ArrayList<DF>(numDirs);
        for (int i = 0; i < numDirs; i++) {
          try {
            // filter problematic directories
            Path tmpDir = new Path(localDirs[i]);
            if(localFS.mkdirs(tmpDir)|| localFS.exists(tmpDir)) {
              try {

                File tmpFile = tmpDir.isAbsolute()
                  ? new File(localFS.makeQualified(tmpDir).toUri())
                  : new File(localDirs[i]);

                DiskChecker.checkDir(tmpFile);
                dirs.add(tmpFile.getPath());
                dfList.add(new DF(tmpFile, 30000));

              } catch (DiskErrorException de) {
                LOG.warn( localDirs[i] + " is not writable\n", de);
              }
            } else {
              LOG.warn( "Failed to create " + localDirs[i]);
            }
          } catch (IOException ie) { 
            LOG.warn( "Failed to create " + localDirs[i] + ": " +
                ie.getMessage() + "\n", ie);
          } //ignore
        }
        localDirs = dirs.toArray(new String[dirs.size()]);
        dirDF = dfList.toArray(new DF[dirs.size()]);
        savedLocalDirs = newLocalDirs;
        
        // randomize the first disk picked in the round-robin selection 
        dirNumLastAccessed = dirIndexRandomizer.nextInt(dirs.size());
      }
    }

    /**
     * 在一个目录下创建一个path 
     * @param path
     * @param checkWrite 如果该值为true,表示创建的目录还要进行校验是否有写权限
     */
    private Path createPath(String path, 
        boolean checkWrite) throws IOException {
      Path file = new Path(new Path(localDirs[dirNumLastAccessed]),
                                    path);
      if (checkWrite) {
        //check whether we are able to create a directory here. If the disk
        //happens to be RDONLY we will fail
        try {
          DiskChecker.checkDir(new File(file.getParent().toUri().getPath()));
          return file;
        } catch (DiskErrorException d) {
          LOG.warn("Disk Error Exception: ", d);
          return null;
        }
      }
      return file;
    }

    /**
     * Get the current directory index.
     * @return the current directory index.
     * 获取当前应该循环第几个目录了
     */
    int getCurrentDirectoryIndex() {
      return dirNumLastAccessed;
    }

    /** Get a path from the local FS. If size is known, we go
     *  round-robin over the set of disks (via the configured dirs) and return
     *  the first complete path which has enough space.
     *  
     *  If size is not known, use roulette selection -- pick directories
     *  with probability proportional to their available space.
     *  申请一个路径,并且要求至少size个磁盘空间大小,并且checkWrite=true表示还得有写权限
     */
    public synchronized Path getLocalPathForWrite(String pathStr, long size, 
        Configuration conf, boolean checkWrite) throws IOException {
      confChanged(conf);
      int numDirs = localDirs.length;
      int numDirsSearched = 0;
      //remove the leading slash from the path (to make sure that the uri
      //resolution results in a valid path on the dir being checked)
      if (pathStr.startsWith("/")) {
        pathStr = pathStr.substring(1);
      }
      Path returnPath = null;
      
      if(size == SIZE_UNKNOWN) {  //do roulette selection: pick dir with probability 申请没有要求所需要的磁盘大小
                    //proportional to available size
        long[] availableOnDisk = new long[dirDF.length];//每一个磁盘可用的空间大小
        long totalAvailable = 0;//可用资源总和
        
            //build the "roulette wheel"
        for(int i =0; i < dirDF.length; ++i) {
          availableOnDisk[i] = dirDF[i].getAvailable();
          totalAvailable += availableOnDisk[i];
        }

        if (totalAvailable == 0){
          throw new DiskErrorException("No space available in any of the local directories.");
        }

        // Keep rolling the wheel till we get a valid path
        Random r = new java.util.Random();
        while (numDirsSearched < numDirs && returnPath == null) {
          long randomPosition = Math.abs(r.nextLong()) % totalAvailable;//随机选择一个可用磁盘的位置
          int dir = 0;
          while (randomPosition > availableOnDisk[dir]) {//找到该磁盘位置,就是最终选择的磁盘
            randomPosition -= availableOnDisk[dir];
            dir++;
          }
          dirNumLastAccessed = dir;
          returnPath = createPath(pathStr, checkWrite);
          if (returnPath == null) {//说明磁盘异常,则清理该磁盘
            totalAvailable -= availableOnDisk[dir];//减少总可用空间
            availableOnDisk[dir] = 0; // skip this disk 减少该磁盘
            numDirsSearched++;
          }
        }
      } else {//申请中要求了需要的磁盘大小
        while (numDirsSearched < numDirs && returnPath == null) {//依次查找磁盘,找到满足size大小的磁盘为止
          long capacity = dirDF[dirNumLastAccessed].getAvailable();
          if (capacity > size) {
            returnPath = createPath(pathStr, checkWrite);
          }
          dirNumLastAccessed++;
          dirNumLastAccessed = dirNumLastAccessed % numDirs; 
          numDirsSearched++;
        } 
      }
      if (returnPath != null) {
        return returnPath;
      }
      
      //no path found
      throw new DiskErrorException("Could not find any valid local " +
          "directory for " + pathStr);
    }

    /** Creates a file on the local FS. Pass size as 
     * {@link LocalDirAllocator.SIZE_UNKNOWN} if not known apriori. We
     *  round-robin over the set of disks (via the configured dirs) and return
     *  a file on the first path which has enough space. The file is guaranteed
     *  to go away when the JVM exits.
     *  创建一个临时磁盘,当JVM关闭的时候,该目录也会被自动释放
     */
    public File createTmpFileForWrite(String pathStr, long size, 
        Configuration conf) throws IOException {

      // find an appropriate directory 申请一个路径,并且要求至少size个磁盘空间大小,并且checkWrite=true表示还得有写权限
      Path path = getLocalPathForWrite(pathStr, size, conf, true);
      File dir = new File(path.getParent().toUri().getPath());
      String prefix = path.getName();

      // create a temp file on this directory 创建一个临时磁盘,当JVM关闭的时候,该目录也会被自动释放
      File result = File.createTempFile(prefix, null, dir);
      result.deleteOnExit();
      return result;
    }

    /** Get a path from the local FS for reading. We search through all the
     *  configured dirs for the file's existence and return the complete
     *  path to the file when we find one 
     *  读取pathStr目录,即循环每一个磁盘,找到第一个有该pathStr目录的即可返回该路径
     */
    public synchronized Path getLocalPathToRead(String pathStr, 
        Configuration conf) throws IOException {
      confChanged(conf);
      int numDirs = localDirs.length;
      int numDirsSearched = 0;
      //remove the leading slash from the path (to make sure that the uri
      //resolution results in a valid path on the dir being checked)
      if (pathStr.startsWith("/")) {
        pathStr = pathStr.substring(1);
      }
      while (numDirsSearched < numDirs) {
        Path file = new Path(localDirs[numDirsSearched], pathStr);
        if (localFS.exists(file)) {
          return file;
        }
        numDirsSearched++;
      }

      //no path found
      throw new DiskErrorException ("Could not find " + pathStr +" in any of" +
      " the configured local directories");
    }

    /**
     * 对所有的磁盘下,去寻找一个path路径,如果存在,则遍历
     * 例如所有的磁盘下,有/aa/bb/cc目录的有3个磁盘.则依次循环这三个磁盘的/aa/bb/cc目录
     */
    private static class PathIterator implements Iterator<Path>, Iterable<Path> {
      private final FileSystem fs;
      private int i = 0;
      private final String[] rootDirs;//从根目录开始循环查找
      private final String pathStr;//查找该目录
      
      private Path next = null;//下一个找到的目录是什么目录

      private PathIterator(FileSystem fs, String pathStr, String[] rootDirs)
          throws IOException {
        this.fs = fs;
        this.pathStr = pathStr;
        this.rootDirs = rootDirs;
        advance();
      }

      @Override
      public boolean hasNext() {
        return next != null;
      }

      /**
       * 寻找下一个
       * @throws IOException
       */
      private void advance() throws IOException {
        while (i < rootDirs.length) {
          next = new Path(rootDirs[i++], pathStr);
          if (fs.exists(next)) {
            return;
          }
        }
        next = null;
      }

      @Override
      public Path next() {
        final Path result = next;
        try {
          advance();
        } catch (IOException ie) {
          throw new RuntimeException("Can't check existance of " + next, ie);
        }
        if (result == null) {
          throw new NoSuchElementException();
        }
        return result;
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("read only iterator");
      }

      @Override
      public Iterator<Path> iterator() {
        return this;
      }
    }

    /**
     * Get all of the paths that currently exist in the working directories.
     * @param pathStr the path underneath the roots
     * @param conf the configuration to look up the roots in
     * @return all of the paths that exist under any of the roots
     * @throws IOException
     */
    synchronized Iterable<Path> getAllLocalPathsToRead(String pathStr,
        Configuration conf) throws IOException {
      confChanged(conf);
      if (pathStr.startsWith("/")) {
        pathStr = pathStr.substring(1);
      }
      return new PathIterator(localFS, pathStr, localDirs);
    }

    /** We search through all the configured dirs for the file's existence
     *  and return true when we find one 
     *  循环每一个磁盘,找到第一个有该pathStr目录的即可返回true
     */
    public synchronized boolean ifExists(String pathStr,Configuration conf) {
      try {
        int numDirs = localDirs.length;
        int numDirsSearched = 0;
        //remove the leading slash from the path (to make sure that the uri
        //resolution results in a valid path on the dir being checked)
        if (pathStr.startsWith("/")) {
          pathStr = pathStr.substring(1);
        }
        while (numDirsSearched < numDirs) {
          Path file = new Path(localDirs[numDirsSearched], pathStr);
          if (localFS.exists(file)) {
            return true;
          }
          numDirsSearched++;
        }
      } catch (IOException e) {
        // IGNORE and try again
      }
      return false;
    }
  }
}
