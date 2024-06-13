import java.awt.Color;
import java.awt.Graphics2D;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.function.Predicate;

import ihs.apcs.spacebattle.Point;
import ihs.apcs.spacebattle.RegistrationData;
import ihs.apcs.spacebattle.commands.RepairCommand;
import lib.*;
import lib.NCli.*;
import lib.NCli.ShipComputer.RadarSystem.*;

/*/

Strategy:

Survivor but when free:
- Path to gold bauble
- Collect close baubles that aren't out of the way
- Repeat

/*/

public class HungryHungryBaubles extends NCli.ShipComputer {
    public static void main(final String[] args) throws Exception {
        if("PRECURSOR".equals(System.getenv("COMPUTERNAME"))) new NCli("localhost", new HungryHungryBaubles()); // local
        else new NCli("10.56.98.229", new HungryHungryBaubles()); // class
    }

    private AvoidanceSystem.Report avoid;

    private Unit bauble;
    private Unit target;
    private Vec gold;

    private Timeout timeout;

    private Predicate<Unit> avoidFilter = unit -> unit.kind == UnitKind.Planet || unit.kind == UnitKind.SpaceMine || unit.kind == UnitKind.Torpedo || unit.kind == UnitKind.Ship || unit.kind == UnitKind.Asteroid;

    private boolean attainable(Vec pos) {
        if(this.gold == null) return true;

        return this.pos.angleTo(gold).dist(this.pos.angleTo(pos)).deg() < 45 // limit to 45deg off objective path
            && this.vel.angle().dist(this.pos.angleTo(pos)).deg() < 60; // limit to 60deg off current vel
    }

    @Override protected RegistrationData init(final int width, final int height) {
        return new RegistrationData("Spear of Adun", new Color(57, 216, 255), 10);
    }

    @Override protected void run() {
        $: while(true) {
            Point goldPoint = this.env.getGameInfo().getObjectiveLocation();
            if(goldPoint != null) this.gold = this.unwrap(new Vec(goldPoint));
            else this.gold = null;

            this.status("SCAN");

            final Set scan = this.radar.scanExtended();

            if(this.vel.mag() > 2 && scan != null) {
                for(final Unit unit : this.radar.mem.filter(this.avoidFilter).units.values()) {
                    this.status("avoid " + unit.kind.toString() + unit.id);
                    this.avoid = this.avoidance.avoid(unit, 5);
                    if(this.avoid != null) {
                        this.status("AVOID");
                        this.avoid.execute(true);
                        this.avoid = null;
                        continue $;
                    }
                }
            }

            if(this.health < 100 && this.energy >= 75) {
                this.status("REPAIR");
                this.control.yield(new RepairCommand(Math.min(100 - (int)this.health, 5)));
                continue $;
            }

            if(this.vel.mag() < 90) {
                this.status("ATTAIN");
                if(this.gold != null) this.control.face(this.gold);
                this.control.boost(Direction.Forward, 0.5, 1, 3, true);
                if(this.gold != null) this.control.steerToFace(this.gold, false);
                continue $;
            }

            if(this.timeout != null && !this.timeout.passed()) continue $;
            else if(this.timeout != null) this.timeout = null;

            if(this.gold != null && this.pos.dist(this.gold) < 100) {
                this.status("STEER TO GOLD BAUBLE");
                this.control.steerToFace(this.gold, false);
                if(this.vel.angle().dist(this.pos.angleTo(this.gold)).deg() < 45) this.timeout = new Timeout(Duration.ofSeconds(5));
                continue $;
            }

            if(this.bauble == null || !this.attainable(this.bauble.pos.as()) || !this.bauble.scan()) {
                this.status("LOCATE LOCAL BAUBLE");

                final Set potential = this.radar.scanFull().filter(
                    unit -> {
                        Vec pos = this.unwrap(unit.pos.as());
                        return unit.kind == UnitKind.Bauble && this.attainable(pos);
                    }
                );
                final double maxValue = potential.reduce(0.0, (unit, acc) -> Math.max(unit.pointValue.or(0.0), acc)); // find highest nearby valued bauble
                potential.filterInPlace(unit -> unit.pointValue.or(0.0) >= maxValue);

                final List<Unit> targets = potential.list();
                targets.sort((a, b) -> Double.compare(this.unwrap(a.pos.as()).dist2(this.pos), this.unwrap(b.pos.as()).dist2(this.pos)));

                if(targets.size() > 0) {
                    this.bauble = targets.get(0);

                    this.status("LOCKON LOCAL BAUBLE");
                    this.control.steerToFace(this.unwrap(this.bauble.pos.as()), false);
                } else {
                    this.bauble = null;

                    this.status("NO LOCAL BAUBLE");
                    if(this.gold != null) this.control.steerToFace(this.gold, false);
                }
            } else {
                this.status("LOCKED ONTO LOCAL BAUBLE");
                this.control.steerToFace(this.unwrap(this.bauble.pos.as()), false);
            }
        }
    }

    @Override public void mapPaint(final Graphics2D render) {
        if(this.avoid != null) this.avoid.paint(render);

        if(this.bauble != null) {
            Vec bp = this.bauble.pos.as();
            render.setColor(switch((int)(double)this.bauble.pointValue.as()) {
                case 5 -> Color.green;
                case 3 -> Color.orange;
                default -> Color.cyan;
            });
            render.fillOval((int)bp.x - 5, (int)bp.y - 5, 10, 10);
        }
    }

    @Override public void mapOverlayPaint(final Graphics2D render, int sx, int sy) {
        if(this.bauble != null) {
            render.setColor(switch((int)(double)this.bauble.pointValue.as()) {
                case 5 -> Color.green;
                case 3 -> Color.orange;
                default -> Color.cyan;
            });
            render.drawString(Integer.toString(this.bauble.id), 16, 16);
        }
    }
}
