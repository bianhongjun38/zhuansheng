import com.zsb.plan.EngTrain;
public class NH4 {
  public static void main(String[] a){
    String[][] c = {{"apple","aple"},{"abc","ab"},{"abc","abcd"},{"ab","abc"},{"","a"},{"a",""}};
    for(String[] p : c){
      try { System.out.println("swap1(""+p[0]+"",""+p[1]+"") = "+EngTrain.swap1(p[0],p[1])); }
      catch(Exception e){ System.out.println("swap1(""+p[0]+"","""+p[1]+"") EX "+e); }
    }
  }
}
