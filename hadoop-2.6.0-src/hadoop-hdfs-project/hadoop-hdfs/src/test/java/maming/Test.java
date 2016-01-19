package maming;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.fs.XAttr;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockStoragePolicySuite;
import org.apache.hadoop.hdfs.util.LongBitFormat;

import com.google.common.collect.Lists;

public class Test {

  public void test1(){
    List<XAttr> xAttrs = Lists.newArrayListWithCapacity(1);
    System.out.println(xAttrs);
    System.out.println(xAttrs.size());
    xAttrs.add(new XAttr.Builder().build());
    System.out.println(xAttrs);
    System.out.println(xAttrs.size());
    xAttrs.add(new XAttr.Builder().build());
    System.out.println(xAttrs);
    System.out.println(xAttrs.size());
    System.out.println(xAttrs.isEmpty());
  }
  
  public static final long LAST_RESERVED_ID = 2 << 14 - 1;
  
  static final int MAX_ARRAY_LENGTH = 1 << 30; //prevent int overflow problem
  
  public void test2(){
    List<String> list = new ArrayList<String>();
    list.add("111");
    list.add("222");
    list.add("333");
    list.add("444");
    list.add("555");
    list.add("666");
    list.remove(list.size()-2);
    System.out.println(list);
    list.remove(1);
  }
  
  
  public void test3(){
    int depth = 0, index=0;
    System.out.println(depth);
  }

  public void test4(){
    System.out.println(Integer.toBinaryString(5));
    System.out.println(Integer.toBinaryString(8));
    System.out.println(Integer.toBinaryString(255));//11111111
    
    System.out.println(30 >>> 48);
  }
  
  public static void main(String[] args) {
    Test test = new Test();
    test.test2();
  }
  
}
