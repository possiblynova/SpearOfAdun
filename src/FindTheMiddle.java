import java.awt.Color;

import ihs.apcs.spacebattle.RegistrationData;
import lib.NCli;

public class FindTheMiddle extends NCli.ShipComputer {
    public static void main(String[] args) throws Exception {
        new NCli("10.56.152.183", new FindTheMiddle());
    }

    private NCli.Vec center;

    private long total;
    private int cycles;

    @Override protected RegistrationData init(int width, int height) {
        this.center = new NCli.Vec(width / 2, height / 2);
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override protected void run() {
        // this.rotateTo(0);
        // this.thrust(Direction.Right, 10, 1, false);
        // this.thrust(Direction.Forward, 10, 1, true);
        while(true) {
            //this.glideContinuous(this.center, 45, 1);
            long start = System.currentTimeMillis();
            this.glide(this.center, 100, false, 0.1, 30);
            this.untilChange(() -> this.env.getGameInfo().getScore(), 0.1, 3);
            this.untilSufficientEnergy(100);
            long dur = System.currentTimeMillis() - start;
            this.total += dur;
            this.cycles++;

            this.status("%d cycles, %.3f average time".formatted(this.cycles, (double)this.total / this.cycles / 1000));
        }
    }
}
