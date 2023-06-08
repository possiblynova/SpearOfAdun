import java.awt.Color;
import java.awt.Graphics2D;
import ihs.apcs.spacebattle.RegistrationData;
import lib.*;
import lib.NCli.*;
import lib.NCli.ShipComputer.RadarSystem.*;

public class Survivor extends NCli.ShipComputer {
    public static void main(final String[] args) throws Exception {
        new NCli("localhost", new Survivor()); // local
        //new NCli("10.56.156.234", new Survivor()); // class
    }

    private Vec target;
    private Vec targetVel;
    private Vec intercept;

    private Vec avoid;
    private double rad;
    private Rotation cur;
    private Rotation straight;
    private Rotation angle;
    private Rotation steer;

    @Override protected RegistrationData init(final int width, final int height) {
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override protected void run() {
        Unit target = null;

        $: while(true) {
            if(this.vel.length() < 15) {
                this.status("ATTAIN");
                this.thrust(Direction.Forward, 0.5, 1, true);
                continue $;
            }

            this.status("SCAN");

            final Set scan = this.radar.scanExtended();

            if(scan != null) {
                for(final Unit unit : scan.filter(Set.celestials).units.values()) {
                    if(unit.influence.stale()) unit.scan();

                    this.avoid = unit.pos.as();
                    this.rad = unit.influence.as().greater();
                    this.cur = this.vel.angle(); // current trajectory
                    this.straight = this.pos.angleTo(unit.pos.as()); // straight path to the body
                    this.angle = Utils.calculateAvoidAngle(this.pos.dist(unit.pos.as()), unit.influence.as().greater() + 28); // angle required to avoid

                    if(this.cur.cmp(this.straight, this.angle)) {
                        this.steer = this.straight.add(this.angle);
                        this.steerTo((int)this.steer.deg(), true);
                        this.status("AVOID");
                        continue $;
                    }
                }

                this.steer = null;

                if(this.energy < 50) continue $;

                if(target == null || !target.scan()) {
                    final Set targets = scan.filter(unit -> (unit.kind == UnitKind.Asteroid || unit.kind == UnitKind.Ship));
                    if(targets.units.size() > 0) {
                        this.status("TARGET");
                        target = targets.units.values().iterator().next();
                        target.scan();
                    } else continue $;
                }

                this.target = target.pos.as();
                this.targetVel = target.vel.as();
                this.intercept = Utils.calculateInterceptPosition(this.pos, this.ang, this.vel, this.target, this.targetVel, NCli.Sim.TORPEDO_SPEED);

                if(Utils.calculateAngleTo(this.pos, this.intercept).sub(this.ang).abs().deg() <= 4) {
                    this.status("FIRING");
                    this.fire(Direction.Forward);
                } else {
                    this.status("LOCKING");
                    this.face(this.intercept);
                }
            }
        }
    }

    @Override public void mapPaint(final Graphics2D render) {
        if(this.steer == null) return;

        render.setColor(Color.white);
        render.drawOval((int)this.avoid.x - (int)this.rad, (int)this.avoid.y - (int)this.rad, (int)this.rad * 2, (int)this.rad * 2);

        render.setColor(Color.red);
        render.drawLine((int)this.pos.x, (int)this.pos.y, (int)this.pos.x + (int)this.vel.x, (int)this.pos.y + (int)this.vel.y);

        final Vec straight = Vec.polar(this.straight.deg(), 100);
        render.setColor(Color.green);
        render.drawLine((int)this.pos.x, (int)this.pos.y, (int)this.pos.x + (int)straight.x, (int)this.pos.y + (int)straight.y);

        final Vec a1 = Vec.polar(this.straight.deg() + this.angle.deg(), 1000);
        final Vec a2 = Vec.polar(this.straight.deg() - this.angle.deg(), 1000);
        render.setColor(Color.yellow);
        render.drawLine((int)this.pos.x, (int)this.pos.y, (int)this.pos.x + (int)a1.x, (int)this.pos.y + (int)a1.y);
        render.drawLine((int)this.pos.x, (int)this.pos.y, (int)this.pos.x + (int)a2.x, (int)this.pos.y + (int)a2.y);

        final Vec steer = Vec.polar(this.steer.deg(), 1000);
        render.setColor(Color.cyan);
        render.drawLine((int)this.pos.x, (int)this.pos.y, (int)this.pos.x + (int)steer.x, (int)this.pos.y + (int)steer.y);

        /*
        if(this.target == null && this.intercept == null) return;

        render.setColor(Color.WHITE);
        render.fillOval((int)this.target.x - 2, (int)this.target.y - 2, 4, 4);

        render.setColor(Color.RED);
        render.drawLine((int)this.target.x, (int)this.target.y, (int)this.target.x + (int)this.targetVel.x, (int)this.target.y + (int)this.targetVel.y);

        render.setColor(Color.GREEN);
        render.drawOval((int)this.intercept.x - 2, (int)this.intercept.y - 2, 4, 4);
        */
    }
}
