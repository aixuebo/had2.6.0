package maming;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;

import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.security.authentication.util.KerberosUtil;

public class Test {

  public void test1(){
    try {
      System.out.println(KerberosUtil.getDefaultRealm());
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    } catch (NoSuchMethodException e) {
      e.printStackTrace();
    } catch (IllegalArgumentException e) {
      e.printStackTrace();
    } catch (IllegalAccessException e) {
      e.printStackTrace();
    } catch (InvocationTargetException e) {
      e.printStackTrace();
    }
  }
  
  public void test2(){
    SecurityManager sm = System.getSecurityManager();
    System.out.println(sm);
  }
  
  
  public void writeTest1(){
    String file = "E://tmp//aaaa.log";
    try{
        FileOutputStream os = new FileOutputStream(new File(file));
        DataOutputStream streamos = new DataOutputStream(os);
        WritableUtils.writeVInt(streamos, 168);
        
        streamos.flush();
        os.flush();
        
        streamos.close();
        os.close();
    }catch(Exception ex){
        ex.printStackTrace();
    }
}

public void readTest1(){
    String file = "E://tmp//aaaa.log";
    try{
        FileInputStream os = new FileInputStream(new File(file));
        DataInputStream streamos = new DataInputStream(os);
        byte[] b = new byte[100];  
        streamos.read(b);
        
        for(byte x:b){
            System.out.println(x);
        }
/*      System.out.println("=="+WritableUtils.decodeVIntSize(b[0]));
        
        System.out.println(streamos.available());
        int x = WritableUtils.readVInt(streamos);
        System.out.println(x);*/
        
    }catch(Exception ex){
        ex.printStackTrace();
    }
}

  public static void main(String[] args) {
    Test test = new Test();
    test.writeTest1();
    test.readTest1();
  }
}
