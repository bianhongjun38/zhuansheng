import com.zsb.plan.EngTrain;
public class NH3 {
  public static void main(String[] a){
    String[][] c = {{"apple","aple"},{"apple","appla"},{"apple","appl"},{"apple","pple"}};
    for(String[] p : c){
      try { System.out.println(p[0]+" vs "+p[1]+" -> "+EngTrain.nearHint(p[0],p[1])); }
      catch(Exception e){ System.out.println(p[0]+" vs "+p[1]+" -> EX "+e); }
    }
  }
}
