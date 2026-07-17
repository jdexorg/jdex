public class EmuFix {
    public static int BASE;
    public int v;
    public String name;
    public EmuFix(String n, int start) { this.name = n; this.v = start; }
    public int doubled() { return this.v * 2; }
    public int plusBase() { return this.v + BASE; }
    public String tag() { return this.name; }
    public static void setBase(int b) { BASE = b; }
    public static int check(EmuFix w) { return w.v + 1; }
    public static int viaCheck(EmuFix w) { return check(w); }
}
