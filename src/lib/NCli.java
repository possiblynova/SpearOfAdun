package lib;
import java.awt.Point;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.*;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;

import ihs.apcs.spacebattle.*;
import ihs.apcs.spacebattle.commands.*;

import lib.NCli.ShipComputer.RadarSystem.*;
import lib.NCli.Sim.*;

public class NCli {
    /**
     * Provides synchronization points between threads
     */
    private static final class Barrier<T> {
        private T value;
        private boolean flag = false;

        /**
         * Blocks this thread until another thread {@link Barrier#resolve}s the barrier
         * @return The value passed into {@link Barrier#resolve}
         * @see Barrier#resolve(T)
         * @apiNote Resolving a barrier can happen before the await, in this case {@code await} will not block and will immediately return the value
         */
        public synchronized T await() {
            try {
                if(!this.flag) this.wait();

                return this.value;
            } catch(final InterruptedException ex) {
                throw new Error(ex);
            } finally {
                this.flag = false;
            }
        }

        /**
         * Resolves a barrier waited for by {@link Barrier#await}
         * @param value The value to resolve the barrier with
         * @see Barrier#await()
         * @apiNote Resolving a barrier can happen before the await, in this case {@code await} will not block and will immediately return the value
         */
        public synchronized void resolve(final T value) {
            this.flag = true;
            this.value = value;

            this.notifyAll();
        }
    }

    /**
     * Provides timeout functionality
     */
    public static final class Timeout {
        /**
         * Duration for a timeout that never completes
         */
        public static final Duration NEVER = Duration.ofNanos(Long.MAX_VALUE);

        /**
         * The time that the timeout should expire (in ms)
         */
        public final Instant end;

        /**
         * Construct a timeout
         * @param duration Duration the timeout should last in seconds
         */
        public Timeout(final Duration duration) { this.end = Instant.now().plus(duration); }

        /**
         * @return Whether the timeout has finished
         */
        public boolean passed() { return Instant.now().isAfter(this.end); }
    }

    /**
     * Data with an expiration
     *
     * Instead of re-initializing this class when new data comes in, consider using the {@link Exp#set} method
     */
    public static final class Exp<T> {
        public static final Duration persist = Duration.ofNanos(Long.MAX_VALUE);

        public Duration ttl;

        private T value;
        private Instant updated = Instant.MIN;

        public Exp(final Duration ttl) { this.ttl = ttl; }
        public Exp(final Duration ttl, final T initial) {
            this(ttl);
            this.set(initial);
        }

        public boolean stale(final Duration ttl) { return Instant.now().isAfter(this.updated.plus(ttl)); }
        public boolean stale() { return this.stale(this.ttl); }
        public boolean fresh(final Duration ttl) { return !this.stale(ttl); }
        public boolean fresh() { return !this.stale(); }
        public Instant last() { return this.updated; }
        public void refresh() { this.updated = Instant.now(); }

        public void setWeak(final T value) { this.value = value; }
        public void set(final T value) {
            this.setWeak(value);
            this.refresh();
        }
        public void setIfExpired(final T value) {
            if(this.stale()) this.set(value);
        }
        public void setOr(final T value) {
            if(value != null) this.set(value);
        }

        public T with(final Duration ttl) { return !this.stale(ttl) ? this.value : null; }
        public T as() { return this.with(this.ttl); }
        public T or(final T or) { return this.fresh() ? this.with(this.ttl) : or; }
        public T unchecked() { return this.value; }

        public T or(final Exp<T> other) { return this.fresh() ? this.as() : other.as(); }
    }

    /**
     * Represents a vector/point
     * @apiNote x right, y down
     */
    public static final class Vec {
        public double x;
        public double y;

        public Vec(final double x, final double y) {
            this.x = x;
            this.y = y;
        }

        public Vec(final double n) { this(n, n); }

        public Vec(final ihs.apcs.spacebattle.Point pt) { this(pt.getX(), pt.getY()); }
        public Vec(final Point pt) { this(pt.getX(), pt.getY()); }

        public static Vec polar(final double angle, final double magnitude) {
            final double radians = Math.toRadians(angle);

            return new Vec(Math.cos(radians) * magnitude, Math.sin(radians) * -magnitude);
        }

        public ihs.apcs.spacebattle.Point point() { return new ihs.apcs.spacebattle.Point(this.x, this.y); }

        public Vec add(final Vec rhs) { return new Vec(this.x + rhs.x, this.y + rhs.y); }
        public Vec sub(final Vec rhs) { return new Vec(this.x - rhs.x, this.y - rhs.y); }
        public Vec mul(final Vec rhs) { return new Vec(this.x * rhs.x, this.y * rhs.y); }
        public Vec scale(final double rhs) { return new Vec(this.x * rhs, this.y * rhs); }
        public Vec div(final Vec rhs) { return new Vec(this.x / rhs.x, this.y / rhs.y); }
        public Vec mod(final Vec rhs) { return new Vec(((this.x % rhs.x) + rhs.x) % rhs.x, ((this.y % rhs.y) + rhs.y) % rhs.y); }
        public double dot(final Vec rhs) { return this.x * rhs.x + this.y * rhs.y; }

        private final double vclos(final double val, double maxn, final double src) {
            final double comp = Math.abs(src - val);

            if(src > maxn / 2) maxn = -maxn;

            if(Math.abs(src - (val + maxn)) < comp) return val + maxn;
            else return val;
        }

        public Vec unwrap(final Vec max, final Vec test) { return new Vec(this.vclos(this.x, max.x, test.x), this.vclos(this.y, max.y, test.y)); }

        public double mag2() { return Math.pow(this.x, 2) + Math.pow(this.y, 2); }
        public double mag() { return Math.sqrt(this.mag2()); }

        public Vec normalize() {
            return this.div(new Vec(this.mag()));
        }

        public Vec withMag(final double magnitude) { return this.normalize().scale(magnitude); }

        public double dist2(final Vec rhs) { return Math.pow(this.x - rhs.x, 2) + Math.pow(this.y - rhs.y, 2); }
        public double dist(final Vec rhs) { return Math.sqrt(this.dist2(rhs)); }

        public Vec rot(final Rot rot) {
            final double radians = rot.rad();

            return new Vec(
                (Math.cos(radians) * this.x) + (Math.sin(radians) * this.y),
                -(Math.sin(radians) * this.x) + (Math.cos(radians) * this.y)
            );
        }

        public Rot angle() { return Rot.rad(-Math.atan2(this.y, this.x)); }
        public Rot angleTo(final Vec dst) { return dst.sub(this).angle(); }

        public Vec swap() { return new Vec(this.y, this.x); }
        public double greater() { return Math.max(this.x, this.y); }
        public double lesser() { return Math.min(this.x, this.y); }

        @Override public String toString() {
            return "<%f, %f>".formatted(this.x, this.y);
        }
    }

    public record Rot(double deg) {
        public static final Rot zero = new Rot(0);

        public Rot(final double deg) { this.deg = Utils.normalizeAngle(deg); }
        public Rot() { this(0); }

        public static Rot deg(final double deg) { return new Rot(deg); }
        public static Rot rad(final double rad) { return Rot.deg(Math.toDegrees(rad)); }

        public double deg() { return this.deg; }
        public double rad() { return Math.toRadians(this.deg); }

        public Rot neg() { return Rot.deg(-this.deg); }
        public Rot abs() { return Rot.deg(Math.abs(this.deg)); }

        public Rot add(final Rot rhs) { return Rot.deg(this.deg + rhs.deg); }
        public Rot sub(final Rot rhs) { return Rot.deg(this.deg - rhs.deg); }
        public Rot mul(final Rot rhs) { return Rot.deg(this.deg * rhs.deg); }
        public Rot div(final Rot rhs) { return Rot.deg(this.deg / rhs.deg); }

        public Rot dist(final Rot rhs) { return Rot.deg(180 - Math.abs(Math.abs(this.deg - rhs.deg) - 180)); }

        public boolean cmp(final Rot rhs, final Rot tolerance) {
            return this.dist(rhs).deg() <= tolerance.deg();
        }

        @Override public String toString() {
            return "%f⁰".formatted(this.deg);
        }
    }

    public static enum Direction {
        Forward,
        Back,
        Left,
        Right;

        public Vec vec() {
            return switch (this) {
                case Forward -> new Vec(1, 0);
                case Back -> new Vec(-1, 0);
                case Left -> new Vec(0, -1);
                case Right -> new Vec(0, 1);
                default -> null; // unreachable
            };
        }

        public Direction flip() {
            return switch (this) {
                case Forward -> Direction.Back;
                case Back -> Direction.Forward;
                case Left -> Direction.Right;
                case Right -> Direction.Left;
                default -> null; // unreachable
            };
        }

        public Direction unsign(final double sign) {
            if(sign < 0) return this.flip();
            else return this;
        }

        public char thrust() {
            return switch (this) {
                case Forward -> 'B';
                case Back -> 'F';
                case Left -> 'R';
                case Right -> 'L';
                default -> 0; // unreachable
            };
        }

        public static Direction fromThrust(final char ch) {
            return switch (ch) {
                case 'B' -> Direction.Forward;
                case 'F' -> Direction.Back;
                case 'R' -> Direction.Left;
                case 'L' -> Direction.Right;
                default -> null; // unreachable
            };
        }

        public char torp() {
            return switch (this) {
                case Forward -> 'F';
                case Back -> 'B';
                default -> throw new Error("invalid torpedo direction"); // invalid
            };
        }
    }

    public static final class Utils {
        public static final <T> T unreachable(final String str) {
            assert false : "unreachable: " + str;
            throw new Error("unreachable: " + str);
        }

        /**
         * Remap a range of values (fromMin to fromMax) to another (toMin to toMax)
         * @param value Value to remap
         * @param fromMin Lower end of the input range
         * @param fromMax Upper end of the input range
         * @param toMin Lower end of the output range
         * @param toMax Upper end of the output range
         * @return Remapped value
         */
        public static final double remap(final double value, final double fromMin, final double fromMax, final double toMin, final double toMax) {
            return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin);
        }

        /**
         * Normalize an angle to (-180, 180]
         * @param angle
         * @return Normalized angle
         */
        public static final int normalizeAngle(int angle) {
            angle = ((angle % 360) + 360) % 360;
            if(angle > 180) angle -= 360;
            return angle;
        }

        /**
         * Normalize an angle to (-180, 180]
         * @param angle
         * @return Normalized angle
         */
        public static final double normalizeAngle(double angle) {
            angle = ((angle % 360) + 360) % 360;
            if(angle > 180) angle -= 360;
            return angle;
        }

        /**
         * Calculate the distance required to stop, using the global Sim.ACCELERATION
         * @param velocity Current velocity
         * @return Distance required to stop
         */
        public static final double calculateDecelerationDistance(final double velocity) {
            return Utils.calculateDecelerationDistance(velocity, Sim.ACCELERATION);
        }

        /**
         * Calculate the distance required to stop
         * @param velocity Current velocity
         * @param accel Maximum achievable acceleration
         * @return Distance required to stop
         */
        public static final double calculateDecelerationDistance(final double velocity, final double acceleration) {
            return (velocity * velocity) / (2 * acceleration);
        }

        /**
         * Calculate the position to intercept a projectile with a target
         * @param pos Current position
         * @param ang Current Angle
         * @param vel Current velocity
         * @param targetPos Target position
         * @param targetVel Target velocity
         * @param projSpeed Projectile speed
         * @return Intercept position
         */
        public static final Vec calculateInterceptPosition(
            final Vec pos,
            final Rot ang,
            final Vec vel,
            final Vec targetPos,
            final Vec targetVel,
            final double projSpeed
        ) {
            // todo: figure out how to handle own velocity being nonzero

            // inputs
            final Vec P1 = pos;
            final Vec P2 = targetPos;
            final Vec vc = targetVel;
            final double vp = projSpeed;

            // calculations
            final Vec c0 = P2.sub(P1);

            final double a0 = c0.normalize().dot(vc);
            final double b = Math.sqrt(Math.pow(c0.mag(), 2) - Math.pow(a0, 2));

            final double qa = vc.mag2() - Math.pow(vp, 2);
            final double qb = 2 * a0 * vc.mag();
            final double qc = Math.pow(a0, 2) + Math.pow(b, 2);
            final double th = (-qb + Math.sqrt(Math.pow(qb, 2) - 4 * qa * qc)) / (2 * qa);

            //System.out.printf("---\nc0=%s\nvc=%s\na0=%f\nb=%f\nqa=%f\nqb=%f\nqc=%f\nth=%f\nP3=%s\n", c0, vc, a0, b, qa, qb, qc, th, P2.add(vc.scale(th)));

            return P2.add(vc.scale(-th * 1.5));
        }

        /**
         * Given a radius of a circle and a distance to the center of that circle,
         * calculates the angle deviation from a direct path to the center to touch the circle tangentially
         * @param distance Distance to the center of the circle
         * @param radius Radius of the circle (if you're trying to avoid planets, consider adding the radius of your own ship to the gravity radius of the planet)
         * @return The deviation from a direct path to the center of the circle that only touches the circle on a single tangent
         */
        public static final Rot calculateAvoidAngle(final double distance, final double radius) {
            if(distance < radius) return Rot.deg(Double.NaN);
            return Rot.rad(Math.asin(radius / distance));
        }
    }

    /**
     * Simulates ship systems
     */
    public final class Sim {
        /**
         * A ShipCommand converted into a simulated command
         */
        abstract class SimCommand {
            /**
             * Whether the initial event should trigger
             */
            boolean initial = true;

            /**
             * The measured {@link SimCommand#timeRemaining} at the start of the command. Used for the progress bar in the client
             */
            double fullTime;

            /**
             * One time execution
             */
            void once() {}

            /**
             * @return Whether or not the command should finish (defaults to {@code true})
             */
            boolean isFinished() { return true; }

            /**
             * Executes every tick the command is active
             */
            void execute() {}

            /**
             * @return The energy cost to start the command (defaults to {@code 0})
             */
            double getInstantEnergyCost() { return 0; }

            /**
             * @return The energy cost per second to continue the command (defaults to {@code 0})
             */
            double getOngoingEnergyCost() { return 0; }

            /**
             * @return Whether or not the command should block
             */
            boolean blocking() { return true; }

            /**
             * @return The name of the command
             */
            abstract String name();

            /**
             * @return Remaining time of the command in seconds
             */
            abstract double timeRemaining();

            /**
             * Reflect-Get-Property: gets a private named property of a ship command
             * @param cmd Command to scoop values out of
             * @param name Property to get
             * @return The boxed value
             * @throws Error All errors from reflection
             */
            protected final Object rgp(final ShipCommand cmd, final String name) {
                try {
                    final Class<?> klass = cmd.getClass();
                    final Field field = klass.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(cmd);
                } catch(final Exception e) {
                    throw new Error(e);
                }
            }
        }

        class SimRotateCommand extends SimCommand {
            double deg;

            public SimRotateCommand(final RotateCommand cmd) {
                this.deg = Utils.normalizeAngle((Integer)this.rgp(cmd, "DEG"));
            }

            @Override boolean isFinished() { return Math.abs(this.deg) < 0.01; }
            @Override void execute() {
                double amt;
                if(this.deg < 0) {
                    amt = -Sim.TURN_RATE * Sim.TIME;
                    if(amt < this.deg) amt = this.deg;
                } else {
                    amt = Sim.TURN_RATE * Sim.TIME;
                    if(amt > this.deg) amt = this.deg;
                }
                this.deg -= amt;
                Sim.this.ang += amt;
                Sim.this.ang = Utils.normalizeAngle(Sim.this.ang);
            }

            @Override double getOngoingEnergyCost() { return 2; }

            @Override String name() { return "ROTATE"; }
            @Override double timeRemaining() { return Math.abs(this.deg / Sim.TURN_RATE); }
        }

        class SimThrustCommand extends SimCommand {
            final Direction dir;
            double duration;
            final double power;
            final boolean blocking;

            public SimThrustCommand(final ThrustCommand cmd) {
                this.dir = Direction.fromThrust((Character)this.rgp(cmd, "DIR"));
                this.duration = (Double)this.rgp(cmd, "DUR");
                this.power = (Double)this.rgp(cmd, "PER");
                this.blocking = (Boolean)this.rgp(cmd, "BLOCK");
            }

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                // todo: limit to max speed (test: can you steer by thrusting perpendicular to vel while at max speed?)
                Sim.this.vel = Sim.this.vel.add(this.dir.vec().scale(this.power * Sim.ACCELERATION * Sim.TIME).rot(Rot.deg(Sim.this.ang)));
                this.duration -= Sim.TIME;
            }

            @Override double getOngoingEnergyCost() { return 3 * this.power; }

            @Override boolean blocking() { return this.blocking; }

            @Override String name() { return "THRUST"; }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        class SimBrakeCommand extends SimCommand {
            final double target;

            public SimBrakeCommand(final BrakeCommand cmd) {
                this.target = (Double)this.rgp(cmd, "PER");
            }

            @Override boolean isFinished() { return Sim.this.vel.mag2() <= this.target; }
            @Override void execute() {
                Sim.this.vel = Sim.this.vel.withMag(Math.max(Sim.this.vel.mag() - Sim.ACCELERATION * Sim.TIME, this.target));
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return true; }

            @Override String name() { return "BRAKE"; }
            @Override double timeRemaining() { return Math.max((Sim.this.vel.mag() - this.target) / Sim.ACCELERATION, 0); }
        }

        class SimSteerCommand extends SimCommand {
            double deg;
            final boolean blocking;

            public SimSteerCommand(final SteerCommand cmd) {
                this.deg = Utils.normalizeAngle((Integer)this.rgp(cmd, "DEG"));
                this.blocking = (Boolean)this.rgp(cmd, "BLOCK");
            }

            @Override boolean isFinished() { return Math.abs(this.deg) < 0.01; }
            @Override void execute() {
                double amt;
                if(this.deg < 0) {
                    amt = -Sim.TURN_RATE * Sim.TIME / 4;
                    if(amt < this.deg) amt = this.deg;
                } else {
                    amt = Sim.TURN_RATE * Sim.TIME / 4;
                    if(amt > this.deg) amt = this.deg;
                }
                this.deg -= amt;

                Sim.this.vel = Sim.this.vel.rot(Rot.deg(amt));
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return this.blocking; }

            @Override String name() { return "STEER"; }
            @Override double timeRemaining() { return Math.abs(this.deg / Sim.TURN_RATE); }
        }

        class SimRadarCommand extends SimCommand {
            final int level;
            final int target;
            double duration;

            public SimRadarCommand(final RadarCommand cmd) {
                this.level = (Integer)this.rgp(cmd, "LVL");
                this.target = (Integer)this.rgp(cmd, "TARGET");
                this.duration = switch(this.level) {
                    case 1 -> 0.03;
                    case 2 -> 0.1;
                    case 3 -> 0.1;
                    case 4 -> 0.15;
                    case 5 -> 0.4;
                    default -> Utils.unreachable("invalid level");
                };
            }

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                this.duration -= Sim.TIME;
            }

            @Override double getOngoingEnergyCost() { return 6; }

            @Override boolean blocking() { return true; }

            @Override String name() {
                return "RADAR : " + switch(this.level) {
                    case 1 -> "COUNT";
                    case 2 -> "BLIND";
                    case 3 -> "SCAN";
                    case 4 -> "EXTENDED";
                    case 5 -> "FULL";
                    default -> Utils.unreachable("invalid level");
                };
            }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        class SimFireCommand extends SimCommand {
            double duration = 0.2;

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                this.duration -= Sim.TIME;
            }

            @Override String name() { return "FIRE"; }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        class SimIdleCommand extends SimCommand {
            double duration;

            public SimIdleCommand(final IdleCommand cmd) {
                this.duration = (Double)this.rgp(cmd, "DUR");
            }

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                this.duration -= Sim.TIME;
            }

            @Override String name() { return "IDLE"; }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        /**
         * The minimum time per game tick
         * @implSpec Should be derived from {@link https://github.com/Mikeware/SpaceBattleArena/blob/master/SBA_Serv/World/WorldMap.py#L24}
         */
        public static final double TIME = 1.0 / 30.0;

        public static final double SHIP_RADIUS = 28;
        public static final double ACCELERATION = 6.58;
        public static final double TURN_RATE = 120.0;
        public static final double MAX_SPEED = 100.0;
        public static final double RADAR_RANGE = 300.0;
        public static final double TORPEDO_SPEED = 15000.0 / 60.0;

        /**
         * The thread the simulator runs on
         */
        final Thread thread = new Thread(this::run, "NCli:Simulator");

        double consumedThisTick = 0;

        String status = "";

        // Simulated Stats
        Vec pos = new Vec(0, 0);
        double ang = 0;
        Vec vel = new Vec(0, 0);
        double energy = 0;
        double health = 0;
        double shields = 0;

        final List<SimCommand> commands = Collections.synchronizedList(new ArrayList<SimCommand>());

        Sim() {
            this.thread.setDaemon(true);
            this.thread.start();
        }

        synchronized void update(final BasicEnvironment env) {
            final ObjectStatus ss = env.getShipStatus();

            this.pos = new Vec(ss.getPosition());
            this.ang = Utils.normalizeAngle(ss.getOrientation());
            this.vel = Vec.polar(ss.getMovementDirection(), ss.getSpeed());
            this.energy = ss.getEnergy();

            this.health = ss.getHealth();
            this.shields = ss.getShieldLevel();
        }

        synchronized void cmd(final ShipCommand cmd) {
            SimCommand scmd;

            if(cmd instanceof RotateCommand) scmd = new SimRotateCommand((RotateCommand)cmd);
            else if(cmd instanceof ThrustCommand) scmd = new SimThrustCommand((ThrustCommand)cmd);
            else if(cmd instanceof BrakeCommand) scmd = new SimBrakeCommand((BrakeCommand)cmd);
            else if(cmd instanceof SteerCommand) scmd = new SimSteerCommand((SteerCommand)cmd);
            else if(cmd instanceof RadarCommand) scmd = new SimRadarCommand((RadarCommand)cmd);
            else if(cmd instanceof FireTorpedoCommand) scmd = new SimFireCommand();
            else if(cmd instanceof IdleCommand) scmd = new SimIdleCommand((IdleCommand)cmd);
            else scmd = null;

            if(scmd != null) this.commands.add(scmd);

            if(this.commands.size() > 4) this.commands.remove(0);
        }

        synchronized void unblock() {
            this.commands.removeIf(SimCommand::blocking);
        }

        boolean consume(final double energy) {
            final boolean sufficient = this.energy >= energy;
            if(sufficient) {
                this.energy -= energy;
                this.consumedThisTick += energy;
            }
            return sufficient;
        }

        synchronized void simulate() {
            if(NCli.this.ready) {
                this.consumedThisTick = 0;

                for(final Iterator<SimCommand> iter = this.commands.iterator(); iter.hasNext();) {
                    final SimCommand cmd = iter.next();
                    if(cmd.initial && !this.consume(cmd.getInstantEnergyCost())) {
                        iter.remove();
                    } else {
                        if(cmd.initial) {
                            cmd.once();
                            cmd.fullTime = cmd.timeRemaining();
                        }
                        cmd.initial = false;

                        final boolean outOfEnergy = !this.consume(cmd.getOngoingEnergyCost() * Sim.TIME);
                        if(outOfEnergy || cmd.isFinished()) {
                            iter.remove();
                        } else cmd.execute();
                    }
                }

                this.pos = this.pos.add(this.vel.mul(new Vec(Sim.TIME))).mod(NCli.this.size);
                this.energy = Math.min(100, this.energy + 4 * Sim.TIME);
            }

            NCli.this.canvas.revalidate();
            NCli.this.canvas.repaint();
        }

        void run() {
            while(true) {
                final long start = System.currentTimeMillis();
                this.simulate();
                final long dur = (long)(Sim.TIME * 1000) - (System.currentTimeMillis() - start);
                if(dur >= 0)
                    try { Thread.sleep(dur); }
                    catch (final InterruptedException ex) { ex.printStackTrace(); }
                else System.out.printf("loop overrun: %.4fs\n", Math.abs((double)dur / 1000));
            }
        }
    }

    private final class Panel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
        private static final Polygon tri = new Polygon(new int[] { -4, 4, 16, 4, -4 }, new int[] { -4, 4, 0, -4, 4 }, 5);

        private static final Font font = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        private static final Font fontBig = new Font(Font.MONOSPACED, Font.PLAIN, 32);

        private static final Color background = new Color(16, 16, 16);
        private static final Color frameBackground = new Color(64, 64, 64);
        private static final Color frameGrid = new Color(32, 32, 32);

        private static final Color foreground = new Color(255, 255, 255);
        private static final Color shipIndicator = new Color(96, 96, 96);

        private static final Color red = new Color(255, 64, 64);
        private static final Color yellow = new Color(255, 255, 64);
        private static final Color green = new Color(64, 255, 64);

        private Sim sim;
        private Graphics2D render;

        private final Rectangle visBounds = new Rectangle();
        private final Rectangle mapBounds = new Rectangle();

        @Override public void paint(final Graphics graphics) {
            this.sim = NCli.this.sim;
            this.render = (Graphics2D)graphics;

            final int width = this.getWidth();
            final int height = this.getHeight();

            this.render.setColor(Panel.background);
            this.render.fillRect(0, 0, width, height);

            if(!NCli.this.ready) {
                this.render.setFont(Panel.fontBig);
                this.render.setColor(Panel.foreground);
                this.center("Press SPACE to connect", width / 2, height / 2);
                return;
            }

            this.render.setFont(Panel.font);

            this.render.setColor(Panel.foreground);
            final String[] lines = this.sim.status.split("\n");
            for(int i = 0; i < lines.length; i++) this.center(lines[i], width / 2, 128 + 12 * i);

            // Stats

            this.trans(AffineTransform.getTranslateInstance(width / 2, 0), () -> {
                this.render.setColor(Panel.frameBackground);
                this.render.fill3DRect(-256, 16, 512, 16, false);

                this.render.setColor(Panel.yellow);
                this.render.fillRect(-256 + 4, 16 + 4, (int)((512 - 8) * (this.sim.energy / 100)), 16 - 8);

                this.center("%.1f%%".formatted(this.sim.energy), 0, 64 - 16);

                final int net = (int)((-this.sim.consumedThisTick / Sim.TIME) + 4);

                if(net == 0) {
                    this.render.setColor(Panel.yellow);
                    this.center("±0", 0, 64);
                } else if(net > 0) {
                    this.render.setColor(Panel.green);
                    this.center("+%d".formatted(net), 0, 64);
                    for(int i = 0; i < net; i++) this.render.fillRect(32 + 10 * i - 4, 64 - 2, 8, 8);
                } else {
                    this.render.setColor(Panel.red);
                    this.center(Integer.toString(net), 0, 64);
                    for(int i = 0; i < -net; i++) this.render.drawRect(-32 - 10 * i - 4, 64 - 2, 8, 8);
                }
            });

            // Commands

            this.frame(width - 16 - 256 - 32, height - 16 - 256 - 32, 256 + 32, 256 + 32, (final Integer w, final Integer h) -> {
                for(int i = 0; i < this.sim.commands.size(); i++) {
                    final Sim.SimCommand cmd = this.sim.commands.get(i);
                    this.frame(16, 16 + 64 * i, 256, 64, false, (final Integer sw, final Integer sh) -> {
                        this.render.setColor(Panel.foreground);

                        if(cmd instanceof final Sim.SimRotateCommand rcmd) {
                            this.render.drawArc(sw / 2 - 16, sh / 2 - 16, 32, 32, (int)(this.sim.ang), (int)(rcmd.deg));
                            this.render.setColor(Panel.shipIndicator);
                            this.ship(false, sw / 2, sh / 2, 16);
                        } else if(cmd instanceof final Sim.SimSteerCommand rcmd) {
                            this.render.drawArc(sw / 2 - 16, sh / 2 - 16, 32, 32, (int)(this.sim.ang), (int)(rcmd.deg));
                            this.render.setColor(Panel.shipIndicator);
                            this.ship(false, sw / 2, sh / 2, 16);
                        }

                        this.render.setColor(Panel.foreground);
                        this.center(cmd.name(), sw / 2, 8);

                        if(cmd.blocking()) {
                            this.render.setColor(Panel.red);
                            this.center(" B", sw - 16, sh - 16);
                        } else {
                            this.render.setColor(Panel.green);
                            this.center("NB", sw - 16, sh - 16);
                        }

                        int cost = (int)cmd.getOngoingEnergyCost();
                        if(cmd.initial) cost += (int)cmd.getInstantEnergyCost();

                        this.render.setColor(Panel.red);
                        this.center(Integer.toString(cost), 16, sh - 16);
                        for(int j = 0; j < cost; j++) this.render.drawRect(32 + 10 * j - 4, sh - 16 - 2, 8, 8);

                        this.render.setColor(Panel.green);
                        this.center("%.2f / %.2f".formatted(cmd.timeRemaining(), cmd.fullTime), sw / 2, sh - 16);
                        this.render.fillRect(4, sh - 4 - 4, (int)((sw - 4 - 4) * (cmd.timeRemaining() / cmd.fullTime)), 2);
                    });
                }
            });

            // Map

            final double aspect = NCli.this.size.x / NCli.this.size.y;
            if(aspect > 1) this.mapBounds.setBounds(16, height - 16 - 512, 512, (int)(512 / aspect));
            else this.mapBounds.setBounds(16, height - 16 - 512, (int)(512 * aspect), 512);
            this.frame(this.mapBounds, (final Integer w, final Integer h) -> {
                this.render.setColor(Panel.frameGrid);
                this.render.drawLine(w / 2, 0, w / 2, h);
                this.render.drawLine(0, h / 2, w, h / 2);

                final AffineTransform trans = new AffineTransform();
                trans.scale(this.mapBounds.width / NCli.this.size.x, this.mapBounds.height / NCli.this.size.y); // should be proportional but whatever
                this.trans(trans, () -> {
                    this.render.setColor(Panel.frameGrid);
                    this.render.drawOval((int)this.sim.pos.x - (int)Sim.RADAR_RANGE, (int)this.sim.pos.y - (int)Sim.RADAR_RANGE, (int)Sim.RADAR_RANGE * 2, (int)Sim.RADAR_RANGE * 2);

                    this.render.setColor(Panel.foreground);
                    this.ship(
                        true,
                        (int)this.sim.pos.x,
                        (int)this.sim.pos.y,
                        Sim.SHIP_RADIUS
                    );

                    NCli.this.ship.mapPaint(this.render);
                });

                NCli.this.ship.mapOverlayPaint(this.render, w, h);
                // final AffineTransform shipTransform = new AffineTransform();
                // shipTransform.translate(256 * aspect * (this.sim.pos.x / NCli.this.size.x), 256 * (this.sim.pos.y / NCli.this.size.y));
                // shipTransform.rotate(Math.toRadians(-this.sim.ang));
                // this.trans(shipTransform, () -> this.ship());
            });

            // Indicator

            this.indicator(16, height - 16 - 512 - 16 - 256, 256, "Velocity: %.1f".formatted(this.sim.vel.mag()), this.sim.vel, 100);

            // Ship

            this.render.setColor(Panel.foreground);

            this.ship(false, width / 2, height / 2, 64);
        }

        private void indicator(final int x, final int y, final int size, final String label, final Vec value, final double max) {
            this.frame(x, y, size, size, (w, h) -> {
                this.render.setColor(Panel.frameGrid);
                this.render.drawLine(w / 2, 0, w / 2, h);
                this.render.drawLine(0, h / 2, w, h / 2);

                this.render.setColor(Panel.foreground);
                this.render.drawString(label, 4, 16);

                this.render.setColor(Panel.shipIndicator);
                this.ship(false, size / 2, size / 2, this.mapToPx(max / Sim.SHIP_RADIUS));

                this.render.setColor(Panel.foreground);
                this.render.drawLine(size / 2, size / 2, size / 2 + (int)(value.x / max * size / 2), size / 2 + (int)(value.y / max * size / 2));
            });
        }

        private void frame(final Rectangle rect, final BiConsumer<Integer, Integer> frame) {
            this.frame((int)rect.getX(), (int)rect.getY(), (int)rect.getWidth(), (int)rect.getHeight(), true, frame);
        }

        private void frame(final int x, final int y, final int width, final int height, final BiConsumer<Integer, Integer> frame) {
            this.frame(x, y, width, height, true, frame);
        }

        private void frame(final int x, final int y, final int width, final int height, final boolean raised, final BiConsumer<Integer, Integer> frame) {
            this.trans(AffineTransform.getTranslateInstance(x, y), () -> {
                this.render.setColor(Panel.frameBackground);
                this.render.fill3DRect(0, 0, width, height, raised);

                this.render.clipRect(0, 0, width, height);
                frame.accept(width, height);
                this.render.setClip(null);
            });
        }

        private void ship(final boolean decorate, final int x, final int y, final double size) {
            final AffineTransform shipTransform = new AffineTransform();
            shipTransform.translate(x, y);
            shipTransform.rotate(Math.toRadians(-this.sim.ang));
            shipTransform.scale(size / 20, size / 20);
            this.trans(shipTransform, () -> {
                this.render.drawPolygon(Panel.tri);

                for(final SimCommand cmd : NCli.this.sim.commands) {
                    if(cmd instanceof SimThrustCommand) {}
                }
            });
        }

        private void center(final String text, final int x, final int y) {
            final FontMetrics metrics = this.render.getFontMetrics();
            final Rectangle2D bounds = metrics.getStringBounds(text, this.render);
            this.render.drawString(text, (int)(x - bounds.getWidth() / 2), (int)(y - bounds.getHeight() / 2 + metrics.getAscent()));
        }

        private void trans(final AffineTransform trans, final Runnable in) {
            final AffineTransform old = this.render.getTransform();
            this.render.transform(trans);
            in.run();
            this.render.setTransform(old);
        }

        private double mapToPx(final double map) {
            return map * (this.mapBounds.width / NCli.this.size.x);
        }

        private double pxToMap(final double px) {
            return px * (NCli.this.size.x / this.mapBounds.width);
        }

        private Vec pxToMapTranslated(final Point px) {
            return new Vec(
                this.pxToMap(px.x - this.mapBounds.x),
                this.pxToMap(px.y - this.mapBounds.y)
            );
        }

        // Input

        @Override public void mouseWheelMoved(final MouseWheelEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseScroll(this.pxToMapTranslated(evt.getPoint()), evt.getPreciseWheelRotation());
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseScroll(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y, evt.getPreciseWheelRotation());
        }
        @Override public void mouseDragged(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseDrag(this.pxToMapTranslated(evt.getPoint()));
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseDrag(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y);
        }
        @Override public void mouseMoved(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseMove(this.pxToMapTranslated(evt.getPoint()));
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseMove(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y);
        }
        @Override public void mousePressed(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseDown(this.pxToMapTranslated(evt.getPoint()), ShipComputer.MouseButton.from(evt.getButton()));
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseDown(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y, ShipComputer.MouseButton.from(evt.getButton()));
        }
        @Override public void mouseClicked(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseClick(this.pxToMapTranslated(evt.getPoint()), ShipComputer.MouseButton.from(evt.getButton()));
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseClick(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y, ShipComputer.MouseButton.from(evt.getButton()));
        }
        @Override public void mouseReleased(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            if(this.mapBounds.contains(evt.getPoint())) NCli.this.ship.mapMouseUp(this.pxToMapTranslated(evt.getPoint()), ShipComputer.MouseButton.from(evt.getButton()));
            else if(this.visBounds.contains(evt.getPoint())) NCli.this.ship.visMouseUp(evt.getX() - this.visBounds.x, evt.getY() - this.visBounds.y, ShipComputer.MouseButton.from(evt.getButton()));
        }
        @Override public void mouseEntered(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
        @Override public void mouseExited(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }

        @Override public void keyTyped(final KeyEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }

        @Override public void keyPressed(final KeyEvent evt) {
            evt.consume();
            if(!NCli.this.ready) {
                if(evt.getKeyCode() == KeyEvent.VK_SPACE) NCli.this.start.resolve(null);
                return;
            }
        }

        @Override public void keyReleased(final KeyEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
    }

    public static abstract class ShipComputer {
        // Classes

        public static enum MouseButton {
            Left,
            Right,
            Middle,
            Back,
            Forward;

            public static final MouseButton from(final int id) {
                return switch(id) {
                    case 1 -> MouseButton.Left;
                    case 2 -> MouseButton.Right;
                    case 3 -> MouseButton.Middle;
                    case 4 -> MouseButton.Back;
                    case 5 -> MouseButton.Forward;
                    default -> throw new Error("invalid button id");
                };
            }
        }

        public final class RadarSystem {
            public enum UnitKind {
                Unknown,

                Ship,
                Asteroid,

                Torpedo,
                SpaceMine,
                Dragon,

                Bauble,

                Planet,
                Star,
                Nebula,
                Quasar,
                BlackHole,
                Wormhole,
                Constellation;

                public static final UnitKind from(final String kind) {
                    return switch(kind) {
                        case "Ship" -> UnitKind.Ship;
                        case "Asteroid" -> UnitKind.Asteroid;

                        case "Torpedo" -> UnitKind.Torpedo;
                        case "SpaceMine" -> UnitKind.SpaceMine;
                        case "Dragon" -> UnitKind.Dragon;

                        case "Bauble" -> UnitKind.Bauble;

                        case "Planet" -> UnitKind.Planet;
                        case "Star" -> UnitKind.Star;
                        case "Nebula" -> UnitKind.Nebula;
                        case "Quasar" -> UnitKind.Quasar;
                        case "BlackHole" -> UnitKind.BlackHole;
                        case "Constellation" -> UnitKind.Constellation;

                        default -> UnitKind.Unknown;
                    };
                }

                public boolean celestial() {
                    return switch(this) {
                        case Planet, Star, Nebula, Quasar, BlackHole, Constellation -> true;
                        default -> false;
                    };
                }

                public boolean persistent() {
                    return switch(this) {
                        case Bauble, Planet, Star, Nebula, Quasar, BlackHole, Constellation -> true;
                        default -> false;
                    };
                }
            }

            public class Unit {
                public final int id;

                public final Exp<Void> seen = new Exp<>(Timeout.NEVER);

                public UnitKind kind = UnitKind.Unknown;
                public String name;

                public final Exp<Vec> pos = new Exp<>(Timeout.NEVER);
                public final Exp<Rot> ang = new Exp<>(Timeout.NEVER);
                public final Exp<Vec> vel = new Exp<>(Timeout.NEVER);

                public final Exp<Double> health = new Exp<>(Timeout.NEVER);
                public final Exp<Double> shields = new Exp<>(Timeout.NEVER);
                public final Exp<Double> energy = new Exp<>(Timeout.NEVER);

                public final Exp<Double> pointValue = new Exp<>(Timeout.NEVER);
                public final Exp<Integer> stored = new Exp<>(Timeout.NEVER);

                public final Exp<Double> radius = new Exp<>(Timeout.NEVER);
                public final Exp<Vec> influence = new Exp<>(Timeout.NEVER);
                public final Exp<Integer> influenceStrength = new Exp<>(Timeout.NEVER);

                public Unit(final int id, final TTLSet ttlset) {
                    this.id = id;
                    this.updateTTLSet(ttlset);
                }

                public boolean scan() {
                    return RadarSystem.this.scanTarget(this.id) != null;
                }

                public void update(final ObjectStatus src, final int level) {
                    this.seen.set(null);

                    if(level == 1) return;

                    // 3/4/5
                    if(level >= 3) this.kind = UnitKind.from(src.getType());
                    if(level == 3 || level == 5) this.name = src.getName();

                    // 2/3/4/5
                    this.pos.set(new Vec(src.getPosition()));

                    // 3/5
                    if(level == 3 || level == 5) {
                        this.ang.set(new Rot(src.getOrientation()));
                        this.vel.set(Vec.polar(src.getMovementDirection(), src.getSpeed()));

                        this.health.set(src.getHealth());
                        this.shields.set(src.getShieldLevel());
                        this.energy.set(src.getEnergy());

                        this.pointValue.set(src.getValue());
                        this.stored.set(src.getNumberStored());

                        this.radius.set(src.getHitRadius());
                        this.influence.set(new Vec(src.getAxisMajorLength(), src.getAxisMinorLength()));
                        this.influenceStrength.set(src.getPullStrength());
                    }
                }

                public void updateTTLSet(final TTLSet ttlset) {
                    this.pos.ttl = ttlset.pos;
                    this.ang.ttl = ttlset.ang;
                    this.vel.ttl = ttlset.vel;

                    this.health.ttl = ttlset.health;
                    this.shields.ttl = ttlset.shields;
                    this.energy.ttl = ttlset.energy;

                    this.pointValue.ttl = ttlset.pointValue;
                    this.stored.ttl = ttlset.stored;

                    this.radius.ttl = ttlset.radius;
                    this.influence.ttl = ttlset.influence;
                    this.influenceStrength.ttl = ttlset.influenceStrength;
                }
            }

            public static final class TTLSet {
                public static final TTLSet persist = new TTLSet();

                static {
                    TTLSet.persist.pos = Exp.persist;
                    TTLSet.persist.ang = Exp.persist;
                    TTLSet.persist.vel = Exp.persist;

                    TTLSet.persist.health = Exp.persist;
                    TTLSet.persist.shields = Exp.persist;
                    TTLSet.persist.energy = Exp.persist;

                    TTLSet.persist.pointValue = Exp.persist;
                    TTLSet.persist.stored = Exp.persist;

                    TTLSet.persist.radius = Exp.persist;
                    TTLSet.persist.influence = Exp.persist;
                    TTLSet.persist.influenceStrength = Exp.persist;
                }

                TTLSet() {}

                public boolean remember = true;
                public boolean persistents = true;

                public Duration nearby = Duration.ofMillis(2000);

                public Duration pos = Duration.ofMillis(500);
                public Duration ang = Duration.ofMillis(1000);
                public Duration vel = Duration.ofMillis(1000);

                public Duration health = Duration.ofMillis(5000);
                public Duration shields = Duration.ofMillis(5000);
                public Duration energy = Duration.ofMillis(1000);

                public Duration pointValue = Duration.ofMillis(5000);
                public Duration stored = Duration.ofMillis(5000);

                public Duration radius = Exp.persist;
                public Duration influence = Exp.persist;
                public Duration influenceStrength = Exp.persist;
            }

            public final class Set {
                public static final Predicate<Unit> celestials = unit -> unit.kind.celestial();

                public final HashMap<Integer, Unit> units;

                public Set() { this(16); }
                public Set(int capacity) { this(new HashMap<>(capacity)); }
                public Set(final HashMap<Integer, Unit> units) { this.units = units; }

                /**
                 * Scans all elements of the set and returns the ones that are still in range, choosing to either individually scan or do a level 5 scan, whichever takes less time
                 */
                public final Set scanAll() {
                    final double target = 0.1;
                    final double full = 0.4;

                    if(this.units.size() * target >= full) { // shortcut! wouldve taken longer to scan each one so we just full scan
                        final Set scan = RadarSystem.this.scanFull();
                        return scan != null ? scan.intersect(this) : null;
                    }

                    Set res = new Set();

                    for(Unit unit : this.units.values()) {
                        if(unit.scan()) res.units.put(unit.id, unit);
                    }

                    return res;
                }

                /**
                 * Returns all elements that are in {@code this} and satisfy {@code predicate}
                 */
                public final Set filter(final Predicate<Unit> predicate) {
                    final Set scan = new Set();

                    for(final Entry<Integer, Unit> entry : this.units.entrySet())
                        if(predicate.test(entry.getValue())) scan.units.put(entry.getKey(), entry.getValue());

                    return scan;
                }

                /**
                 * Removes all elements from {@code this} that do not satisfy {@code predicate}
                 */
                public final Set filterInPlace(final Predicate<Unit> predicate) {
                    this.units.entrySet().removeIf(entry -> !predicate.test(entry.getValue()));
                    return this;
                }

                /**
                 * {@code this ∪ with} - Returns all elements that are in {@code this} and/or {@code with}
                 */
                public final Set union(final Set with) {
                    final Set union = new Set();
                    union.units.putAll(this.units);
                    if(with != null) union.units.putAll(with.units);
                    return union;
                }

                /**
                 * {@code this = this ∪ with} - Adds all elements of {@code with} to {@code this}
                 */
                public final Set unionInPlace(final Set with) {
                    if(with != null) this.units.putAll(with.units);
                    return this;
                }

                /**
                 * {@code this ∩ with} - Returns all elements that are in both {@code this} and {@code with}
                 */
                public final Set intersect(final Set with) {
                    if(with == null) return new Set(0);
                    return this.filter(unit -> with.units.containsKey(unit.id));
                }

                /**
                 * {@code this = this ∩ with} - Removes elements of {@code this} that are not in {@code with}
                 */
                public final Set intersectInPlace(final Set with) {
                    if(with == null) {
                        this.units.clear();
                        return this;
                    }
                    return this.filterInPlace(unit -> with.units.containsKey(unit.id));
                }

                /**
                 * {@code this \ with} - Returns all elements that are in {@code this} but not {@code with}
                 */
                public final Set difference(final Set with) {
                    if(with == null) return this.clone();
                    return this.filter(unit -> !with.units.containsKey(unit.id));
                }

                /**
                 * {@code this = this \ with} - Removes elements of {@code this} that are in {@code with}
                 */
                public final Set differenceInPlace(final Set with) {
                    if(with == null) return this;
                    return this.filterInPlace(unit -> !with.units.containsKey(unit.id));
                }

                /**
                 * {@code this Δ with} - Returns elements that are in either {@code this} or {@code with} but not both
                 */
                public final Set symmetricDifference(final Set with) {
                    if(with == null) return this.clone();
                    return this.difference(with).union(with.difference(this));
                }

                /**
                 * {@code this = this Δ with} - Removes elements from {@code this} that are also in {@code with}, and adds elements that are in {@code with} but not in {@code this}
                 */
                public final Set symmetricDifferenceInPlace(final Set with) {
                    if(with == null) return this;
                    return this.differenceInPlace(with).unionInPlace(with.difference(this));
                }

                public final Unit find(final Predicate<Unit> predicate) {
                    for(final Unit unit : this.units.values()) {
                        if(predicate.test(unit)) return unit;
                    }

                    return null;
                }

                public final <T> T reduce(final T initial, final BiFunction<Unit, T, T> reducer) {
                    T acc = initial;

                    for(final Unit unit : this.units.values()) acc = reducer.apply(unit, acc);

                    return acc;
                }

                public final List<Unit> list() {
                    return this.units.values().stream().collect(Collectors.toList());
                }

                @SuppressWarnings("unchecked") @Override public final Set clone() {
                    return new Set((HashMap<Integer, Unit>)this.units.clone());
                }
            }

            RadarSystem() {}

            private final ShipComputer ship = ShipComputer.this;

            public final TTLSet ttlset = new TTLSet();

            public final Exp<Integer> nearby = new Exp<>(this.ttlset.nearby);
            public final Set mem = new Set(new HashMap<>());
            public Instant ping = Instant.MIN;

            public final void pushResults(final RadarResults res, final int level) {
                this.ping = Instant.now();
                this.nearby.set(res.getNumObjects());

                if(level == 1) return;

                for(final ObjectStatus obj : res) {
                    if(!this.mem.units.containsKey(obj.getId())) this.mem.units.put(obj.getId(), new Unit(obj.getId(), this.ttlset));

                    final Unit unit = this.mem.units.get(obj.getId());
                    unit.update(obj, level);

                    if(this.ttlset.persistents && unit.kind.persistent()) unit.updateTTLSet(TTLSet.persist);
                }
            }

            public final int scanNearby() {
                this.ship.control.yield(new RadarCommand(1));

                final RadarResults res = this.ship.env.getRadar();
                if(res != null) {
                    this.pushResults(res, 1);
                    return res.getNumObjects();
                } else return -1;
            }

            public final Set scanBlind() {
                this.ship.control.yield(new RadarCommand(2));

                final RadarResults res = this.ship.env.getRadar();
                if(res != null) {
                    this.pushResults(res, 2);
                    return new Set((HashMap<Integer, Unit>)res.stream().collect(Collectors.toMap(obj -> obj.getId(), obj -> this.mem.units.get(obj.getId()))));
                } return null;
            }

            public final Unit scanTarget(final int id) {
                this.ship.control.yield(new RadarCommand(3, id));

                final RadarResults res = this.ship.env.getRadar();
                if(res != null) {
                    this.pushResults(res, 3);
                    return this.mem.units.get(id);
                } else return null;
            }

            public final Set scanExtended() {
                this.ship.control.yield(new RadarCommand(4));

                final RadarResults res = this.ship.env.getRadar();
                if(res != null) {
                    this.pushResults(res, 4);
                    return new Set((HashMap<Integer, Unit>)res.stream().collect(Collectors.toMap(obj -> obj.getId(), obj -> this.mem.units.get(obj.getId()))));
                } return null;
            }

            public final Set scanFull() {
                this.ship.control.yield(new RadarCommand(5));

                final RadarResults res = this.ship.env.getRadar();
                if(res != null) {
                    this.pushResults(res, 5);
                    return new Set((HashMap<Integer, Unit>)res.stream().collect(Collectors.toMap(obj -> obj.getId(), obj -> this.mem.units.get(obj.getId()))));
                } return null;
            }

            public final void updateTTLSet() {
                for(final Unit unit : this.mem.units.values()) unit.updateTTLSet(this.ttlset.persistents && !unit.kind.persistent() ? TTLSet.persist : this.ttlset);
            }
        }

        public class AvoidanceSystem {
            public class Report {
                public Unit target; // Target to avoid
                public Vec pos;
                public double collision; // Avoidance radius
                public Rot trajectory; // Current trajectory direction
                public Rot straight; // Direction to the target
                public Rot angle; // Angle deviation from straight required to avoid target
                public Rot steer; // Angle currently steering to
                public boolean escape; // Whether or not escape routine is needed

                public final void paint(final Graphics2D render) {
                    final int px = (int)AvoidanceSystem.this.ship.cli.sim.pos.x;
                    final int py = (int)AvoidanceSystem.this.ship.cli.sim.pos.y;

                    render.setColor(Color.white);
                    render.drawOval((int)this.pos.x - (int)this.collision, (int)this.pos.y - (int)this.collision, (int)this.collision * 2, (int)this.collision * 2);
                    render.drawOval(px - (int)Sim.SHIP_RADIUS, py - (int)Sim.SHIP_RADIUS, (int)Sim.SHIP_RADIUS * 2, (int)Sim.SHIP_RADIUS * 2);

                    if(this.escape) {
                        final Vec escape = AvoidanceSystem.this.ship.pos.sub(this.pos).withMag(1000);
                        render.setColor(Color.cyan);
                        render.drawLine(px, py, px + (int)escape.x, py + (int)escape.y);
                    } else {
                        final Vec straight = Vec.polar(this.straight.deg(), 100);
                        render.setColor(Color.green);
                        render.drawLine(px, py, px + (int)straight.x, py + (int)straight.y);

                        final Vec a1 = Vec.polar(this.straight.deg() + this.angle.deg(), 1000);
                        final Vec a2 = Vec.polar(this.straight.deg() - this.angle.deg(), 1000);
                        render.setColor(Color.yellow);
                        render.drawLine(px, py, px + (int)a1.x, py + (int)a1.y);
                        render.drawLine(px, py, px + (int)a2.x, py + (int)a2.y);

                        final Vec steer = Vec.polar(this.steer.deg(), 1000);
                        render.setColor(Color.cyan);
                        render.drawLine(px, py, px + (int)steer.x, py + (int)steer.y);
                    }

                    final Vec nv = AvoidanceSystem.this.ship.vel.normalize();
                    render.setColor(Color.red);
                    render.drawLine(px, py, px + (int)nv.x * 1000, py + (int)nv.y * 1000);
                }

                public final void execute(final boolean blocking) {
                    final ShipComputer ship = AvoidanceSystem.this.ship;

                    if(this.escape) {
                        while(ship.pos.dist(this.pos) < this.collision + Sim.SHIP_RADIUS) {
                            ship.control.thrustVectoredWorld(ship.pos.sub(this.pos).normalize(), 0.5, 1, true);
                        }
                    } else ship.control.steerTo(this.steer, blocking);
                }
            }

            AvoidanceSystem() {}

            private final ShipComputer ship = ShipComputer.this;

            public Report avoid(final Unit unit) {
                if(unit.radius.stale() && !unit.scan()) return null;

                final Report rep = new Report();

                rep.target = unit;
                rep.pos = this.ship.unwrap(unit.pos.as());
                rep.collision = Math.max(unit.influence.as().greater(), unit.radius.as());
                rep.trajectory = this.ship.vel.angle(); // current trajectory
                rep.straight = this.ship.pos.angleTo(rep.pos); // straight path to the body
                rep.angle = Utils.calculateAvoidAngle(this.ship.pos.dist(rep.pos), rep.collision + Sim.SHIP_RADIUS); // angle required to avoid

                if(Double.isNaN(rep.angle.deg())) rep.escape = true;
                else if(rep.trajectory.cmp(rep.straight, rep.angle)) {
                    final Rot left = rep.straight.add(rep.angle);
                    final Rot right = rep.straight.sub(rep.angle);

                    if(rep.trajectory.dist(left).deg() < rep.trajectory.dist(right).deg()) rep.steer = left;
                    else rep.steer = right;
                } else return null;

                return rep;
            }
        }

        public class TargetingSystem {
            TargetingSystem() {}

            private final ShipComputer ship = ShipComputer.this;
        }

        public class ControlSystem {
            ControlSystem() {}

            private final ShipComputer ship = ShipComputer.this;

            // Internal

            final Barrier<ShipCommand> tx = new Barrier<>();
            final Barrier<BasicEnvironment> rx = new Barrier<>();

            final void execute() {
                this.ship.env = this.rx.await();
                this.updateStatus();
                this.ship.run();
                while(true) this.idle(1);
            }

            final void updateStatus() {
                this.ship.unit.update(this.ship.env.getShipStatus(), 3);

                this.ship.object = this.ship.env.getShipStatus();
                this.ship.pos = new Vec(this.ship.object.getPosition());
                this.ship.ang = new Rot(this.ship.object.getOrientation());
                this.ship.speed = this.ship.object.getSpeed();
                this.ship.vel = Vec.polar(this.ship.object.getMovementDirection(), this.ship.speed);

                this.ship.health = this.ship.object.getHealth();
                this.ship.shield = this.ship.object.getShieldLevel();
                this.ship.energy = this.ship.object.getEnergy();
            }

            // Commands

            /**
             * Yields a command to the server
             * @param cmd The command to yield, will block if the command is blocking
             */
            public final void yield(final ShipCommand cmd) {
                this.tx.resolve(cmd);
                this.ship.env = this.rx.await();
                this.updateStatus();
            }

            // Movement - Thrust

            /**
             * Fires thrusters to accelerate the ship
             * @param dir Direction to accelerate in relative to the ship (x-forward, y-right)
             * @param dur Time to accelerate for
             * @param power Power to accelerate with (0.1-1)
             */
            public final void thrust(final Direction dir, final double dur, final double power, final boolean blocking) { this.thrustVectored(dir.vec(), dur, power, blocking); }

            public final void boost(final Direction dir, final double time, final double power, final int boosts, final boolean blocking) {
                for(int i = 0; i < Math.min(Math.max(boosts, 0), 3); i++) this.thrustVectored(dir.vec(), time, power, false);
                this.thrustVectored(dir.vec(), time, power, blocking);
            }

            /**
             * Fires thrusters to accelerate the ship, using vectored thrust (1 or 2 thrusters firing)
             * @param dir Direction to accelerate in relative to the ship (x-forward, y-right)
             * @param dur Time to accelerate for
             * @param power Power to accelerate with (0.1-1)
             */
            public final void thrustVectored(Vec dir, final double dur, final double power, final boolean blocking) {
                dir = dir.normalize();

                final double absx = Math.abs(dir.x);
                final double absy = Math.abs(dir.y);

                if(absx >= 0.1) this.yield(new ThrustCommand(Direction.Forward.unsign(dir.x).thrust(), dur, power * absx, blocking && absy < 0.1));
                if(absy >= 0.1) this.yield(new ThrustCommand(Direction.Right.unsign(dir.y).thrust(), dur, power * absy, blocking));
            }

            /**
             * Fires thrusters to accelerate the ship, using vectored thrust (1 or 2 thrusters firing)
             * @param dir Direction to accelerate in relative to the world (x-right, y-down)
             * @param dur Time to accelerate for
             * @param power Power to accelerate with (0.1-1)
             */
            public final void thrustVectoredWorld(final Vec dir, final double dur, final double power, final boolean blocking) { this.thrustVectored(dir.rot(this.ship.ang.neg()), dur, power, blocking); }

            public final void brake() { this.brake(0); }

            public final void brake(final double percent) { this.yield(new BrakeCommand(percent)); }

            // Movement - Rotation

            public final boolean rotate(final Rot offset) {
                if(offset.abs().deg() < 1) return false;
                this.yield(new RotateCommand((int)offset.deg));
                return true;
            }

            public final boolean rotateTo(final Rot angle) {
                return this.rotate(angle.sub(this.ship.ang));
            }

            public final void face(final Vec target) {
                while(this.rotateTo(this.ship.pos.angleTo(target)));
            }

            public final boolean steer(final Rot offset, final boolean blocking) {
                if(offset.abs().deg() < 1) return false;
                this.yield(new SteerCommand((int)offset.deg(), blocking));
                return true;
            }

            public final boolean steerTo(final Rot angle, final boolean blocking) {
                return this.steer(angle.sub(this.ship.vel.angle()), blocking);
            }

            public final void steerToFace(final Vec target, final boolean ensure) {
                while(this.steerTo(this.ship.pos.angleTo(target), true) && ensure);
            }

            // Movement - Subroutines

            public final void glide(final Vec target, final double maxSpeed, final boolean faceTarget, final double thrustDuration, final Duration timeout) {
                final Timeout tout = new Timeout(timeout);

                while(!tout.passed()) {
                    final Vec dir = target.sub(this.ship.pos);
                    final double distance = dir.mag();

                    if(faceTarget) this.face(target);

                    if(distance <= Utils.calculateDecelerationDistance(this.ship.speed)) break;
                    else if(this.ship.speed < maxSpeed) {
                        if(faceTarget) this.thrust(Direction.Forward, thrustDuration, 1, false);
                        else {
                            this.thrustVectoredWorld(dir, thrustDuration, 1, false);
                        }
                    } else this.idle(0.1);

                    this.steerToFace(target, true);
                }

                this.brake();
            }

            public final void glideBoost(final Vec target, final double maxSpeed, final int boosts, final double thrustDuration, final Duration timeout) {
                final Timeout tout = new Timeout(timeout);

                while(!tout.passed()) {
                    final Vec dir = target.sub(this.ship.pos);
                    final double distance = dir.mag();

                    this.face(target);

                    if(distance <= Utils.calculateDecelerationDistance(this.ship.speed, Sim.ACCELERATION * (boosts + 1))) break;
                    else if(this.ship.speed < maxSpeed) this.boost(Direction.Forward, thrustDuration, 1, boosts, true);
                    else this.idle(0.1);

                    this.steerToFace(target, true);
                }

                this.boost(Direction.Back, this.ship.vel.mag() / (Sim.ACCELERATION * (boosts + 1)), 1, boosts, true);
            }

            // Torpedo

            public final void fire(final Direction dir) {
                this.yield(new FireTorpedoCommand(dir.torp()));
            }

            // Misc

            public final void idle(final double time) { this.yield(new IdleCommand(time)); }

            public final void until(final Supplier<Boolean> condition, final double interval, final Duration timeout) {
                final Timeout tout = new Timeout(timeout);
                while(!(condition.get() || tout.passed())) this.idle(interval);
            }

            public final <T> void untilChange(final Supplier<T> value, final double interval, final Duration timeout) {
                final T initial = value.get();
                this.until(() -> !value.get().equals(initial), interval, timeout);
            }

            public final <T> void untilSufficientEnergy(final double energy) {
                this.until(() -> this.ship.energy >= energy, 0.1, Timeout.NEVER);
            }
        }

        // Internals

        NCli cli;
        final boolean visEnabled;

        {
            boolean en = true;
            try {
                this.getClass().getDeclaredMethod("visPaint", this.getClass(), Graphics2D.class);
            } catch(final NoSuchMethodException ex) {
                en = false;
            }
            this.visEnabled = en;
        }

        // For the user to implement

        protected RegistrationData init(final int width, final int height) {
            return new RegistrationData("Ship @" + Integer.toHexString((int)(Math.random() * 65535)), new Color(255, 255, 255, 255), 9);
        }

        protected abstract void run();

        protected void destroyed(final String by) {}

        protected void mouseWheelMoved(final MouseWheelEvent evt) {}
        protected void mouseDragged(final MouseEvent evt) {}
        protected void mouseMoved(final MouseEvent evt) {}
        protected void mouseClicked(final MouseEvent evt) {}
        protected void mousePressed(final MouseEvent evt) {}
        protected void mouseReleased(final MouseEvent evt) {}
        protected void mouseEntered(final MouseEvent evt) {}
        protected void mouseExited(final MouseEvent evt) {}

        // Overrides - Map

        /**
         * Paints over the map in *world coordinates*
         *
         * If you want to paint the map without scaling, consider using {@link ShipComputer#mapOverlayPaint}
         */
        protected void mapPaint(final Graphics2D render) {}

        /**
         * Paints over the map in *offset window coordinates*
         *
         * If you want to paint the map without scaling, consider using {@link ShipComputer#mapOverlayPaint}
         */
        protected void mapOverlayPaint(final Graphics2D render, final int sx, final int sy) {}

        protected void mapMouseDown(final Vec pos, final ShipComputer.MouseButton button) {}
        protected void mapMouseUp(final Vec pos, final ShipComputer.MouseButton button) {}
        protected void mapMouseClick(final Vec pos, final ShipComputer.MouseButton button) {}
        protected void mapMouseMove(final Vec pos) {}
        protected void mapMouseDrag(final Vec pos) {}
        protected void mapMouseScroll(final Vec pos, final double delta) {}

        // Overrides - Vis

        protected void visPaint(final Graphics2D render, final int sx, final int sy) {}

        protected void visMouseDown(final int x, final int y, final ShipComputer.MouseButton button) {}
        protected void visMouseUp(final int x, final int y, final ShipComputer.MouseButton button) {}
        protected void visMouseClick(final int x, final int y, final ShipComputer.MouseButton button) {}
        protected void visMouseMove(final int x, final int y) {}
        protected void visMouseDrag(final int x, final int y) {}
        protected void visMouseScroll(final int x, final int y, final double delta) {}

        // Utiltiies

        protected final RadarSystem radar = new RadarSystem();
        protected final AvoidanceSystem avoidance = new AvoidanceSystem();
        protected final TargetingSystem targeting = new TargetingSystem();
        protected final ControlSystem control = new ControlSystem();

        protected final Unit unit = this.radar.new Unit(0, TTLSet.persist);

        protected BasicEnvironment env;
        protected ObjectStatus object;
        protected Vec pos = new Vec(0, 0);
        protected Rot ang = new Rot();
        protected double speed = 0;
        protected Vec vel = new Vec(0, 0);

        protected double health = 0;
        protected double shield = 0;
        protected double energy = 0;

        protected final void status(final String status) { this.cli.sim.status = status; }

        protected final Vec unwrap(final Vec wrapped) { return wrapped.unwrap(this.cli.size, this.pos); }
    }

    // NCli

    private Vec size;

    private boolean ready = false;
    private final Barrier<Void> start = new Barrier<>();

    private final ShipComputer ship;
    private final Thread coroutine;
    private final Sim sim;
    private final JFrame frame = new JFrame("NCli");
    private final Panel canvas = new Panel();

    public NCli(final String ip, final ShipComputer ship) {
        ship.cli = this;

        this.canvas.setSize(1250, 750);
        this.canvas.addMouseListener(this.canvas);
        this.canvas.addMouseMotionListener(this.canvas);
        this.canvas.addMouseWheelListener(this.canvas);

        this.frame.add(this.canvas);
        this.frame.addKeyListener(this.canvas);
        this.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.frame.setVisible(true);
        this.frame.getContentPane().setPreferredSize(new Dimension(1250, 750));
        this.frame.getContentPane().setMinimumSize(new Dimension(1250, 750));
        this.frame.getContentPane().setMaximumSize(new Dimension(1250, 750));
        this.frame.pack();
        this.frame.setResizable(false);
        try {
            this.frame.setIconImage(ImageIO.read(new File("icon.png")));
        } catch(final IOException e) {
            e.printStackTrace();
        }

        this.ship = ship;

        this.coroutine = new Thread(ship.control::execute, "NCli:Coroutine");
        this.coroutine.setDaemon(true);
        this.coroutine.setUncaughtExceptionHandler((thread, exception) -> {
            System.err.println("NCli:Coroutine Thread: uncaught exception:");
            exception.printStackTrace();
        });
        this.coroutine.start();

        this.sim = new Sim();

        this.start.await();

        TextClient.run(ip, new BasicSpaceship() {
            @Override public RegistrationData registerShip(final int numImages, final int width, final int height) {
                NCli.this.size = new Vec(width, height);

                return ship.init(width, height);
            }

            @Override public ShipCommand getNextCommand(final BasicEnvironment env) {
                synchronized(NCli.this.sim) {
                    NCli.this.sim.unblock();

                    ship.control.rx.resolve(env);
                    final ShipCommand cmd = ship.control.tx.await();

                    NCli.this.sim.cmd(cmd);
                    NCli.this.sim.update(env);
                    NCli.this.ready = true;

                    return cmd;
                }
            }

            @Override public void shipDestroyed(final String by) {
                ship.destroyed(by);
            }
        });
    }
}
