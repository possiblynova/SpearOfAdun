import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

import ihs.apcs.spacebattle.ObjectStatus;
import ihs.apcs.spacebattle.RadarResults;
import ihs.apcs.spacebattle.RegistrationData;
import lib.NCli;
import lib.NCli.Direction;
import lib.NCli.Utils;
import lib.NCli.Vec;

public class Survivor extends NCli.ShipComputer {
    public static void main(final String[] args) throws Exception {
        //new NCli("172.30.226.161", new Survivor()); // school
        new NCli("192.168.0.106", new Survivor()); // home
    }

    private Vec target;
    private Vec targetVel;
    private Vec intercept;

    @Override protected RegistrationData init(final int width, final int height) {
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override protected void run() {
        int target = 69420;
        this.thrustVectoredWorld(new Vec(1, 0), 3, 1, true);

        while(true) {
            final ObjectStatus obj = this.radarTarget(target);

            if(obj == null) {
                while(true) {
                    this.status("Searching");
                    final List<ObjectStatus> radar = this.radarExtended().getByType("Ship");
                    if(radar.size() > 0) {
                        this.status("Target acquired");
                        target = radar.get(0).getId();
                        break;
                    }
                }
                continue;
            }

            this.target = new Vec(obj.getPosition());
            this.targetVel = Vec.polar(obj.getMovementDirection(), obj.getSpeed());
            this.intercept = Utils.calculateInterceptPosition(this.pos, this.vel, this.target, this.targetVel, NCli.Sim.TORPEDO_SPEED);

            System.out.println("a: " + Utils.calculateAngleTo(this.pos, this.intercept).sub(this.ang).abs().deg());
            if(Utils.calculateAngleTo(this.pos, this.intercept).sub(this.ang).abs().deg() <= 4) {
                this.status("Target locked, firing");
                this.fire(Direction.Forward);
            } else {
                this.status("Target locking");
                this.face(this.intercept);
            }
            this.untilSufficientEnergy(30);

            /*
            if(!this.face(this.intercept)) {
                this.status("Target locked, firing at " + this.intercept);
                this.fire(Direction.Forward);
            } else this.status("Target locking");
            */
        }
    }

    @Override public void mapPaint(final Graphics2D render) {
        if(this.target == null && this.intercept == null) return;

        render.setColor(Color.WHITE);
        render.fillOval((int)this.target.x - 2, (int)this.target.y - 2, 4, 4);

        render.setColor(Color.RED);
        render.drawLine((int)this.target.x, (int)this.target.y, (int)this.target.x + (int)this.targetVel.x, (int)this.target.y + (int)this.targetVel.y);

        render.setColor(Color.GREEN);
        render.drawOval((int)this.intercept.x - 2, (int)this.intercept.y - 2, 4, 4);
    }
}
