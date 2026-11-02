import tr.hastane.sayim.ServerCheck;
public class CheckTest { public static void main(String[] a) {
  int f = 0;
  ServerCheck.Sonuc s = ServerCheck.dene("http://127.0.0.1:" + a[0]); System.out.println("sayim: " + s.tur + " " + s.bilgi); if (s.tur != 0) f++;
  s = ServerCheck.dene("http://127.0.0.1:1"); System.out.println("kapali: " + s.tur + " " + s.bilgi); if (s.tur != 2) f++;
  s = ServerCheck.dene("http://127.0.0.1:" + a[0] + "/x"); System.out.println("yanlis yol (durum 404?): " + s.tur); 
  System.exit(f); } }
