package maming.mbean;

import java.io.IOException;

import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.StandardMBean;

import org.apache.hadoop.hdfs.server.datanode.metrics.FSDatasetMBean;
import org.apache.hadoop.metrics2.util.MBeans;

public class Test {

  ObjectName mbeanName;
  
  public void test1(){
    try {
      TestImple t = new TestImple();
      StandardMBean bean = new StandardMBean(t,FSDatasetMBean.class);
      mbeanName = MBeans.register("MAMING", "FSDatasetState-555" , bean);
      while(true){
        Thread.sleep(10000l);
        if(t.getDfsUsed() == 100l){
          System.out.println("sss");
        }else{
          System.out.println("ffffffff");
        }
      }
    } catch (NotCompliantMBeanException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (InterruptedException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
   public static void main(String[] args) {
    Test test = new Test();
    test.test1();
  }
}
