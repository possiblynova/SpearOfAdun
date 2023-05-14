import java.awt.Color;

import ihs.apcs.spacebattle.RegistrationData;
import ihs.apcs.spacebattle.commands.CloakCommand;
import ihs.apcs.spacebattle.commands.FireTorpedoCommand;
import ihs.apcs.spacebattle.commands.RaiseShieldsCommand;
import ihs.apcs.spacebattle.commands.RotateCommand;
import ihs.apcs.spacebattle.commands.SteerCommand;
import ihs.apcs.spacebattle.commands.ThrustCommand;
import lib.NCli;

public class FindTheMiddle extends NCli.ShipComputer {
    public static void main(String[] args) throws Exception {
        new NCli("192.168.0.121", new FindTheMiddle());
    }

    private NCli.Vec center;

    @Override
    protected RegistrationData init(int width, int height) {
        this.center = new NCli.Vec(width / 2, height / 2);
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override
    protected void run() {
        while(true) {
            this.glide(this.center, 100, false, 0.2, 45);
            this.untilChange(() -> this.env.getGameInfo().getScore(), 0.1, 3);
        }
    }
}
