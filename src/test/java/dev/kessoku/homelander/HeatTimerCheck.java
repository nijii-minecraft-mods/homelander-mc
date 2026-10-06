package dev.kessoku.homelander;
public final class HeatTimerCheck {
    public static void main(String[] args) {
        HeatTimer timer = new HeatTimer();
        for (int t=0;t<39;t++) require(!timer.tick("leaves",40,t));
        require(timer.tick("leaves",40,39));
        for(int t=40;t<79;t++) require(!timer.tick("leaves",40,t));
        require(timer.tick("leaves",40,79));
        timer.reset();
        for(int t=80;t<110;t++) require(!timer.tick("wood",60,t));
        for(int t=110;t<169;t++) require(!timer.tick("other",60,t));
        require(timer.tick("other",60,169));
        timer.reset();
        require(!timer.tick("other",60,170));
        System.out.println("PASS: continuous heating, ignition cooldown, target change and reset");
    }
    private static void require(boolean value) {if(!value) throw new AssertionError("Unexpected heat timer state");}
}
