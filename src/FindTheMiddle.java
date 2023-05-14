import java.awt.Color;

import ihs.apcs.spacebattle.RegistrationData;
import lib.NCli;

public class FindTheMiddle extends NCli.ShipComputer {
    public static void main(String[] args) throws Exception {
        new NCli("192.168.0.106", new FindTheMiddle());
    }

    private NCli.Vec center;

    @Override
    protected RegistrationData init(int width, int height) {
        this.center = new NCli.Vec(width / 2, height / 2);
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 9);
    }

    @Override
    protected void run() {
        while(true) {
            this.glide(this.center, 100, true, 0.2, 45);
            this.untilChange(() -> this.env.getGameInfo().getScore(), 0.1, 3);
        }
    }
}
