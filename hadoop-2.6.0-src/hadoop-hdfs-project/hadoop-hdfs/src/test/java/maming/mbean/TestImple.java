package maming.mbean;

import java.io.IOException;

import org.apache.hadoop.hdfs.server.datanode.metrics.FSDatasetMBean;

public class TestImple implements FSDatasetMBean{

  private long l = 50l;
  @Override
  public long getBlockPoolUsed(String bpid) throws IOException {
    // TODO Auto-generated method stub
    l = 100;
    return 1000;
  }

  @Override
  public long getDfsUsed() throws IOException {
    // TODO Auto-generated method stub
    return l;
  }
  
  @Override
  public long getCapacity() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getRemaining() throws IOException {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public String getStorageInfo() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public int getNumFailedVolumes() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getCacheUsed() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getCacheCapacity() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getNumBlocksCached() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getNumBlocksFailedToCache() {
    // TODO Auto-generated method stub
    return 0;
  }

  @Override
  public long getNumBlocksFailedToUncache() {
    // TODO Auto-generated method stub
    return 0;
  }

}
