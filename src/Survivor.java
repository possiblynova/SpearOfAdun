import java.awt.Color;
import java.awt.Graphics2D;
import ihs.apcs.spacebattle.RegistrationData;
import ihs.apcs.spacebattle.commands.RepairCommand;
import lib.*;
import lib.NCli.*;
import lib.NCli.ShipComputer.RadarSystem.*;

public class Survivor extends NCli.ShipComputer {
    public static void main(final String[] args) throws Exception {
        if("PRECURSOR".equals(System.getenv("COMPUTERNAME"))) new NCli("localhost", new Survivor()); // local
        else new NCli("10.56.156.234", new Survivor()); // class
    }

    private Vec target;
    private Vec targetVel;
    private Vec intercept;

    private AvoidanceSystem.Report avoid;

    @Override protected RegistrationData init(final int width, final int height) {
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override protected void run() {
        Unit target = null;

        $: while(true) {
            this.status("SCAN");

            final Set scan = this.radar.scanExtended();

            if(scan != null) {
                for(final Unit unit : scan.filter(Set.celestials.or(unit -> unit.kind == UnitKind.Torpedo || unit.kind == UnitKind.Ship || unit.kind == UnitKind.Asteroid)).units.values()) {
                    this.avoid = this.avoidance.avoid(unit);
                    if(this.avoid != null) {
                        this.avoid.execute(true);
                        this.avoid = null;
                        continue $;
                    }
                }
            }

            if(this.vel.mag() < 15) {
                this.status("ATTAIN");
                this.control.thrust(Direction.Forward, 0.5, 1, true);
                continue $;
            }

            if(this.health < 100 && this.energy > 75) {
                this.control.yield(new RepairCommand(Math.min(100 - (int)this.health, 5)));
                continue $;
            }

            if(this.energy < 50) continue $;

            if(target == null || !target.scan()) {
                final Set targets = scan.filter(unit -> (unit.kind == UnitKind.Asteroid || unit.kind == UnitKind.Ship));
                if(targets.units.size() > 0) {
                    this.status("TARGET");
                    target = targets.units.values().iterator().next();
                    if(!target.scan()) continue $;
                } else continue $;
            }

            this.target = target.pos.as();
            this.targetVel = target.vel.as();
            this.intercept = Utils.calculateInterceptPosition(this.pos, this.ang, this.vel, this.target, this.targetVel, NCli.Sim.TORPEDO_SPEED);

            if(this.pos.angleTo(this.intercept).dist(this.ang).abs().deg() <= 4) {
                this.status("FIRING");
                this.control.fire(Direction.Forward);
            } else {
                this.status("LOCKING");
                this.control.face(this.intercept);
            }
        }
    }

    @Override public void mapPaint(final Graphics2D render) {
        if(this.avoid != null) this.avoid.paint(render);
    }
}
