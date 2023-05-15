package lib;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.*;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;

import ihs.apcs.spacebattle.*;
import ihs.apcs.spacebattle.commands.*;

public class NCli {
    /**
     * Provides synchronization points between threads
     */
    private static final class Async<T> {
        private T value;
        private boolean flag = false;

        /**
         * Blocks this thread until another thread {@link Async#fulfill}s the demand
         * @return The value passed into {@code fulfill}
         * @see Async#fulfill(T)
         * @apiNote Fulfilling a demand can happen before the await, in this case {@code await} will not block
         */
        public synchronized T await() {
            try {
                if(!this.flag) this.wait();

                return this.value;
            } catch(InterruptedException ex) {
                throw new RuntimeException(ex);
            } finally {
                this.flag = false;
            }
        }

        /**
         * Fulfills a demand started by {@link Async#await}
         * @param value The value to fulfill the demand with
         * @see Async#await()
         * @apiNote Fulfilling a demand can happen before the await, in this case {@code await} will not block
         */
        public synchronized void fulfill(T value) {
            this.flag = true;
            this.value = value;

            this.notifyAll();
        }
    }

    /**
     * Provides timeout functionality
     */
    private static final class Timeout {
        /**
         * The time that the timeout should expire (in ms)
         */
        public final long end;

        /**
         * Construct a timeout
         * @param duration Duration the timeout should last in seconds
         */
        public Timeout(double duration) {
            if(duration <= 0.01) this.end = Long.MAX_VALUE;
            else this.end = System.currentTimeMillis() + (long)(duration * 1000);
        }

        /**
         * @return Whether the timeout has finished
         */
        public boolean passed() { return System.currentTimeMillis() >= this.end; }
    }

    /**
     * Simulates ship systems
     */
    private final class Sim {
        abstract class SimCommand {
            boolean initial = true;

            void once() {}
            boolean isFinished() { return true; }
            void execute() {}

            double getInstantEnergyCost() { return 0; }
            double getOngoingEnergyCost() { return 0; }

            boolean blocking() { return true; }

            abstract String description();
            abstract double timeRemaining();

            protected final Object rgp(ShipCommand cmd, String name) {
                try {
                    Class<?> klass = cmd.getClass();
                    Field field = klass.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(cmd);
                } catch(Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }

        class SimRotateCommand extends SimCommand {
            double deg;

            public SimRotateCommand(RotateCommand cmd) {
                this.deg = Utils.normalizeAngle((Integer)this.rgp(cmd, "DEG"));
            }

            @Override boolean isFinished() { return Math.abs(this.deg) < 0.01; }
            @Override void execute() {
                double amt;
                if(this.deg < 0) {
                    amt = -Sim.this.turnRate * Sim.TIME;
                    if(amt < this.deg) amt = this.deg;
                } else {
                    amt = Sim.this.turnRate * Sim.TIME;
                    if(amt > this.deg) amt = this.deg;
                }
                this.deg -= amt;
                Sim.this.ang += amt;
                Sim.this.ang = Utils.normalizeAngle(Sim.this.ang);
            }

            @Override double getOngoingEnergyCost() { return 2; }

            @Override String description() { return "Rotate"; }
            @Override double timeRemaining() { return this.deg / Sim.this.turnRate; }
        }

        class SimThrustCommand extends SimCommand {
            final Direction dir;
            double duration;
            final double power;
            final boolean blocking;

            public SimThrustCommand(ThrustCommand cmd) {
                this.dir = Direction.fromThrust((Character)this.rgp(cmd, "DIR"));
                this.duration = (Double)this.rgp(cmd, "DUR");
                this.power = (Double)this.rgp(cmd, "PER");
                this.blocking = (Boolean)this.rgp(cmd, "BLOCK");
            }

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                // todo: limit to max speed (test: can you steer by thrusting perpendicular to vel while at max speed?)
                Sim.this.vel = Sim.this.vel.add(this.dir.vec().scale(this.power * Sim.ACCELERATION * Sim.TIME).rotate(-Sim.this.ang));
                this.duration -= Sim.TIME;
            }

            @Override double getOngoingEnergyCost() { return 3 * this.power; }

            @Override boolean blocking() { return this.blocking; }

            @Override String description() { return "Thrust"; }
            @Override double timeRemaining() { return this.duration; }
        }

        class SimBrakeCommand extends SimCommand {
            final double target;

            public SimBrakeCommand(BrakeCommand cmd) {
                this.target = (Double)this.rgp(cmd, "PER");
            }

            @Override boolean isFinished() { return Sim.this.vel.length2() <= this.target; }
            @Override void execute() {
                Sim.this.vel = Sim.this.vel.withLength(Math.max(Sim.this.vel.length() - Sim.ACCELERATION * Sim.TIME, this.target));
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return true; }

            @Override String description() { return "Brake"; }
            @Override double timeRemaining() { return (Sim.this.vel.length() - this.target) / Sim.ACCELERATION; }
        }

        class SimSteerCommand extends SimCommand {
            double deg;
            final boolean blocking;

            public SimSteerCommand(SteerCommand cmd) {
                this.deg = Utils.normalizeAngle((Integer)this.rgp(cmd, "DEG"));
                this.blocking = (Boolean)this.rgp(cmd, "BLOCK");
            }

            @Override boolean isFinished() { return Math.abs(this.deg) < 0.01; }
            @Override void execute() {
                double amt;
                if(this.deg < 0) {
                    amt = -Sim.this.turnRate * Sim.TIME;
                    if(amt < this.deg) amt = this.deg;
                } else {
                    amt = Sim.this.turnRate * Sim.TIME;
                    if(amt > this.deg) amt = this.deg;
                }
                this.deg -= amt;

                Sim.this.vel = Sim.this.vel.rotate(amt);
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return this.blocking; }

            @Override String description() { return "Rotate"; }
            @Override double timeRemaining() { return this.deg / Sim.this.turnRate; }
        }

        class SimIdleCommand extends SimCommand {
            double duration;

            public SimIdleCommand(IdleCommand cmd) {
                this.duration = (Double)this.rgp(cmd, "DUR");
            }

            @Override boolean isFinished() { return this.duration <= 0; }
            @Override void execute() {
                this.duration -= Sim.TIME;
            }

            @Override String description() { return "Idle"; }
            @Override double timeRemaining() { return this.duration; }
        }

        /**
         * The minimum time per game tick
         * @implSpec Should be derived from {@link https://github.com/Mikeware/SpaceBattleArena/blob/master/SBA_Serv/World/WorldMap.py#L24}
         */
        public static final double TIME = 1.0 / 30.0;

        public static final double ACCELERATION = 6.6;

        /**
         * The thread the simulator runs on
         */
        final Thread thread = new Thread(this::run, "NCli:Simulator");

        public int turnRate = 120;
        public double maxSpeed = 100;
        public int radarRange = 300;

        public String status = "";

        Vec pos = new Vec(0, 0);
        double ang = 0;
        Vec vel = new Vec(0, 0);
        double energy = 0;

        double lastHealth = 0;
        double lastShield = 0;

        double consumedThisTick = 0;

        final List<SimCommand> commands = Collections.synchronizedList(new ArrayList<SimCommand>());

        Sim() {
            this.thread.setDaemon(true);
            this.thread.start();
        }

        synchronized void update(BasicEnvironment env) {
            final ObjectStatus ss = env.getShipStatus();

            this.turnRate = ss.getRotationSpeed();
            this.maxSpeed = ss.getMaxSpeed();
            this.radarRange = ss.getRadarRange();

            this.pos = new Vec(ss.getPosition());
            this.ang = Utils.normalizeAngle(ss.getOrientation());
            this.vel = Vec.polar(ss.getMovementDirection(), ss.getSpeed());
            this.energy = ss.getEnergy();

            this.lastHealth = ss.getHealth();
            this.lastShield = ss.getShieldLevel();
        }

        synchronized void cmd(ShipCommand cmd) {
            SimCommand scmd;

            if(cmd instanceof RotateCommand) scmd = new SimRotateCommand((RotateCommand)cmd);
            else if(cmd instanceof ThrustCommand) scmd = new SimThrustCommand((ThrustCommand)cmd);
            else if(cmd instanceof BrakeCommand) scmd = new SimBrakeCommand((BrakeCommand)cmd);
            else if(cmd instanceof SteerCommand) scmd = new SimSteerCommand((SteerCommand)cmd);
            else if(cmd instanceof IdleCommand) scmd = new SimIdleCommand((IdleCommand)cmd);
            else scmd = null;

            if(scmd != null) this.commands.add(scmd);

            if(this.commands.size() > 4) this.commands.remove(0);
        }

        synchronized void unblock() {
            this.commands.removeIf(SimCommand::blocking);
        }

        boolean consume(double energy) {
            boolean sufficient = this.energy >= energy;
            if(sufficient) {
                this.energy -= energy;
                this.consumedThisTick += energy;
            }
            return sufficient;
        }

        synchronized void simulate() {
            this.consumedThisTick = 0;

            for(Iterator<SimCommand> iter = this.commands.iterator(); iter.hasNext();) {
                SimCommand cmd = iter.next();
                // if(!this.consume(cmd.getOngoingEnergyCost() * Sim.TIME) || cmd.execute()) {
                //     cmd.end();
                //     iter.remove();
                // }
                if(cmd.initial && !this.consume(cmd.getInstantEnergyCost())) {
                    iter.remove();
                } else {
                    if(cmd.initial) cmd.once();
                    cmd.initial = false;

                    boolean outOfEnergy = !this.consume(cmd.getOngoingEnergyCost() * Sim.TIME);
                    if(outOfEnergy || cmd.isFinished()) {
                        iter.remove();
                    } else cmd.execute();
                }
            }

            this.pos = this.pos.add(this.vel.mul(new Vec(Sim.TIME))).mod(new Vec(NCli.this.width, NCli.this.height));
            this.energy = Math.min(100, this.energy + 4 * Sim.TIME);

            NCli.this.canvas.revalidate();
            NCli.this.canvas.repaint();
        }

        void run() {
            while(true) {
                long start = System.currentTimeMillis();
                this.simulate();
                long dur = (long)(Sim.TIME * 1000) - (System.currentTimeMillis() - start);
                if(dur > 0)
                    try { Thread.sleep(dur); }
                    catch (InterruptedException ex) { ex.printStackTrace(); }
            }
        }
    }

    private final class Panel extends JPanel {
        private final Polygon tri = new Polygon(new int[] { -4, 12, -4 }, new int[] { -4, 0, 4 }, 3);

        private final Font font = new Font("Roboto Mono", Font.PLAIN, 14);

        private final Color background = new Color(16, 16, 16);
        private final Color frameBackground = new Color(64, 64, 64);
        private final Color frameGrid = new Color(32, 32, 32);

        private final Color foreground = new Color(255, 255, 255);
        private final Color shipIndicator = new Color(96, 96, 96);

        private final Color energy = new Color(255, 255, 64);

        @Override public void paint(Graphics graphics) {
            Graphics2D render = (Graphics2D)graphics;

            int width = this.getWidth();
            int height = this.getHeight();

            render.setColor(this.background);
            render.fillRect(0, 0, width, height);

            render.setFont(this.font);

            if(!NCli.this.ready) {
                render.setColor(this.foreground);
                render.drawString("Connecting", 8, 16);
                return;
            }

            render.setColor(this.foreground);
            this.center(render, NCli.this.sim.status, width / 2, 256);

            // Stats

            this.trans(render, AffineTransform.getTranslateInstance(width / 2, 0), () -> {
                render.setColor(this.frameBackground);
                render.fill3DRect(-256, 16, 512, 16, false);

                render.setColor(this.energy);
                render.fillRect(-256 + 4, 16 + 4, (int)((512 - 8) * (NCli.this.sim.energy / 100)), 16 - 8);

                this.center(render, "%.1f%%".formatted(NCli.this.sim.energy), 0, 64 - 16);

                int net = (int)((-NCli.this.sim.consumedThisTick / Sim.TIME) + 4);

                if(net == 0) {
                    render.setColor(this.energy);
                    this.center(render, "±0", 0, 64);
                } else if(net > 0) {
                    render.setColor(new Color(64, 255, 64));
                    this.center(render, "+%d".formatted(net), 0, 64);
                    for(int i = 0; i < net; i++) render.fillRect(32 + 10 * i - 4, 64 - 4, 8, 8);
                } else {
                    render.setColor(new Color(255, 64, 64));
                    this.center(render, Integer.toString(net), 0, 64);
                    for(int i = 0; i < -net; i++) render.fillRect(-32 - 10 * i - 4, 64 - 4, 8, 8);
                }
            });

            // Commands

            this.frame(render, width - 16 - 256 - 32, height - 16 - 512 - 32, 256 + 32, 512 + 32, (Integer w, Integer h) -> {
                for(int i = 0; i < NCli.this.sim.commands.size(); i++) {
                    Sim.SimCommand cmd = NCli.this.sim.commands.get(i);
                    this.frame(render, 16, 16 + 128 * i, 256, 128, false, (Integer sw, Integer sh) -> {
                        this.center(render, cmd.description(), sw / 2, sh / 2);
                    });
                }
            });

            // Map

            final double aspect = (double)NCli.this.width / (double)NCli.this.height;
            this.frame(render, 16, height - 16 - 256, (int)(256 * aspect), 256, (Integer w, Integer h) -> {
                render.setColor(this.frameGrid);
                render.drawLine(w / 2, 0, w / 2, h);
                render.drawLine(0, h / 2, w, h / 2);

                render.setColor(this.foreground);
                AffineTransform shipTransform = new AffineTransform();
                shipTransform.translate(256 * aspect * (NCli.this.sim.pos.x / NCli.this.width), 256 * (NCli.this.sim.pos.y / NCli.this.height));
                shipTransform.rotate(Math.toRadians(-NCli.this.sim.ang));
                this.trans(render, shipTransform, () -> this.ship(render));
            });

            // Indicator

            this.indicator(render, 16, height - 16 - 256 - 16 - 256, 256, "Velocity: %.1f".formatted(NCli.this.sim.vel.length()), NCli.this.sim.vel, 100);

            // Ship

            render.setColor(this.foreground);

            AffineTransform shipTransform = new AffineTransform();
            shipTransform.translate(width / 2, height / 2);
            shipTransform.scale(4, 4);
            shipTransform.rotate(Math.toRadians(-NCli.this.sim.ang));
            this.trans(render, shipTransform, () -> this.ship(render));
        }

        private void indicator(Graphics2D render, int x, int y, int size, String label, Vec value, double max) {
            this.frame(render, x, y, size, size, (w, h) -> {
                render.setColor(this.frameGrid);
                render.drawLine(w / 2, 0, w / 2, h);
                render.drawLine(0, h / 2, w, h / 2);

                render.setColor(this.foreground);
                render.drawString(label, 4, 16);

                render.setColor(this.shipIndicator);
                AffineTransform shipTransform = new AffineTransform();
                shipTransform.translate(size / 2, size / 2);
                shipTransform.scale(2, 2);
                shipTransform.rotate(Math.toRadians(-NCli.this.sim.ang));
                this.trans(render, shipTransform, () -> this.ship(render));

                render.setColor(this.foreground);
                render.drawLine(size / 2, size / 2, size / 2 + (int)(value.x / max * size / 2), size / 2 + (int)(value.y / max * size / 2));
            });
        }

        private void frame(Graphics2D render, int x, int y, int width, int height, BiConsumer<Integer, Integer> frame) {
            this.frame(render, x, y, width, height, true, frame);
        }

        private void frame(Graphics2D render, int x, int y, int width, int height, boolean raised, BiConsumer<Integer, Integer> frame) {
            this.trans(render, AffineTransform.getTranslateInstance(x, y), () -> {
                render.setColor(this.frameBackground);
                render.fill3DRect(0, 0, width, height, raised);

                render.clipRect(0, 0, width, height);
                frame.accept(width, height);
                render.setClip(null);
            });
        }

        private void ship(Graphics2D render) {
            render.drawPolygon(this.tri);
            render.fillRect(-1, -1, 2, 2);
        }

        private void center(Graphics2D render, String text, int x, int y) {
            FontMetrics metrics = render.getFontMetrics();
            Rectangle2D bounds = metrics.getStringBounds(text, render);
            render.drawString(text, (int)(x - bounds.getWidth() / 2), (int)(y - bounds.getHeight() / 2 + metrics.getAscent()));
        }

        private void trans(Graphics2D render, AffineTransform trans, Runnable in) {
            AffineTransform old = ((Graphics2D)render).getTransform();
            render.transform(trans);
            in.run();
            render.setTransform(old);
        }
    }

    /**
     * Represents a vector/point
     * @apiNote x right, y down
     */
    public static final class Vec {
        public double x;
        public double y;

        public Vec(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public Vec(double n) { this(n, n); }

        public Vec(Point pt) { this(pt.getX(), pt.getY()); }

        public static Vec polar(double angle, double magnitude) {
            double radians = Math.toRadians(angle);

            return new Vec(Math.cos(radians) * magnitude, Math.sin(radians) * -magnitude);
        }

        public Point point() { return new Point(this.x, this.y); }

        public Vec add(Vec rhs) { return new Vec(this.x + rhs.x, this.y + rhs.y); }
        public Vec sub(Vec rhs) { return new Vec(this.x - rhs.x, this.y - rhs.y); }
        public Vec mul(Vec rhs) { return new Vec(this.x * rhs.x, this.y * rhs.y); }
        public Vec scale(double rhs) { return new Vec(this.x * rhs, this.y * rhs); }
        public Vec div(Vec rhs) { return new Vec(this.x / rhs.x, this.y / rhs.y); }
        public Vec mod(Vec rhs) { return new Vec(((this.x % rhs.x) + rhs.x) % rhs.x, ((this.y % rhs.y) + rhs.y) % rhs.y); }

        public double length2() { return Math.pow(this.x, 2) + Math.pow(this.y, 2); }
        public double length() { return Math.sqrt(this.length2()); }

        public Vec normalize() {
            return this.div(new Vec(this.length()));
        }

        public Vec withLength(double magnitude) { return this.normalize().scale(magnitude); }

        public double dist2(Vec rhs) { return Math.pow(this.x - rhs.x, 2) + Math.pow(this.y - rhs.y, 2); }
        public double dist(Vec rhs) { return Math.sqrt(this.dist2(rhs)); }

        public Vec rotate(double angle) {
            double radians = Math.toRadians(angle);

            return new Vec(
                (Math.cos(radians) * this.x) - (Math.sin(radians) * this.y),
                (Math.sin(radians) * this.x) + (Math.cos(radians) * this.y)
            );
        }

        public double angle() { return Math.toDegrees(Math.atan2(this.y, this.x)); }

        public Vec swap() { return new Vec(this.y, this.x); }

        @Override public String toString() {
            return "<%f, %f>".formatted(this.x, this.y);
        }
    }

    public static enum Direction {
        Forward,
        Back,
        Left,
        Right;

        public Vec vec() {
            switch(this) {
                case Forward: return new Vec(1, 0);
                case Back: return new Vec(-1, 0);
                case Left: return new Vec(0, -1);
                case Right: return new Vec(0, 1);
                default: return null; // unreachable
            }
        }

        public Direction flip() {
            switch(this) {
                case Forward: return Direction.Back;
                case Back: return Direction.Forward;
                case Left: return Direction.Right;
                case Right: return Direction.Left;
                default: return null; // unreachable
            }
        }

        public Direction unsign(double sign) {
            if(sign < 0) return this.flip();
            else return this;
        }

        public char thrust() {
            switch(this) {
                case Forward: return 'B';
                case Back: return 'F';
                case Left: return 'R';
                case Right: return 'L';
                default: return 0; // unreachable
            }
        }

        public static Direction fromThrust(char ch) {
            switch(ch) {
                case 'B': return Direction.Forward;
                case 'F': return Direction.Back;
                case 'R': return Direction.Left;
                case 'L': return Direction.Right;
                default: return null; // unreachable
            }
        }

        public char torp() {
            switch(this) {
                case Forward: return 'F';
                case Back: return 'B';
                default: return 0; // invalid
            }
        }
    }

    public static abstract class ShipComputer {
        // Internals

        NCli cli;
        final Async<ShipCommand> tx = new Async<>();
        final Async<BasicEnvironment> rx = new Async<>();

        final void execute() {
            this.env = this.rx.await();
            this.updateStatus();
            this.run();
            while(true) this.idle(1);
        }

        final void updateStatus() {
            this.ship = this.env.getShipStatus();
            this.pos = new Vec(this.ship.getPosition());
            this.ang = Utils.normalizeAngle(this.ship.getOrientation());
            this.speed = this.ship.getSpeed();
            this.vel = Vec.polar(this.ship.getMovementDirection(), this.speed);
            this.health = this.ship.getHealth();
            this.shield = this.ship.getShieldLevel();
            this.energy = this.ship.getEnergy();
        }

        // For the user to implement

        protected RegistrationData init(int width, int height) {
            return new RegistrationData("Ship @" + Integer.toHexString((int)(Math.random() * 65535)), new Color(255, 255, 255, 255), 9);
        }

        protected abstract void run();

        protected void destroyed(String by) {};

        // Utiltiies

        protected BasicEnvironment env;
        protected ObjectStatus ship;
        protected Vec pos = new Vec(0, 0);
        protected int ang = 0;
        protected double speed = 0;
        protected Vec vel = new Vec(0, 0);
        protected double health = 0;
        protected double shield = 0;
        protected double energy = 0;

        protected final void status(String status) {
            this.cli.sim.status = status;
        }

        /**
         * Yields a command to the server
         * @param cmd The command to yield, will block if the command is blocking
         */
        private final void yield(ShipCommand cmd) {
            this.tx.fulfill(cmd);
            this.env = this.rx.await();
            this.updateStatus();
        }

        // Radar

        /**
         * Pings the radar for nearby objects
         *
         * @return The amount of objects nearby
         * @apiNote Equivalent to an L1 scan
         * @apiNote Blocking
         * @apiNote 0.03s duration
         */
        protected final int radarCount() {
            this.yield(new RadarCommand(1));
            return this.env.getRadar().getNumObjects();
        }

        /**
         * Pings the radar for limited information on nearby objects
         *
         * @return The ID and position of nearby objects
         * @apiNote Equivalent to an L2 scan
         * @apiNote Blocking
         * @apiNote 0.1s duration
         */
        protected final RadarResults radarBlind() {
            this.yield(new RadarCommand(2));
            return this.env.getRadar();
        }

        /**
         * Pings the radar for full information on a specific object
         *
         * @param target The target ID to scan
         * @return Full object info of the target
         * @apiNote Equivalent to an L3 scan
         * @apiNote Blocking
         * @apiNote 0.1s duration
         */
        protected final ObjectStatus radarTarget(int target) {
            this.yield(new RadarCommand(3, target));
            return this.env.getRadar().get(0);
        }

        /**
         * Pings the radar for slightly less limited information on nearby objects
         *
         * @return The ID, position, and type of nearby objects
         * @apiNote Equivalent to an L4 scan
         * @apiNote Blocking
         * @apiNote 0.15s duration
         */
        protected final RadarResults radarExtended() {
            this.yield(new RadarCommand(4));
            return this.env.getRadar();
        }

        /**
         * Pings the radar for full information on nearby objects
         *
         * @return Full object information of nearby objects
         * @apiNote Equivalent to an L5 scan
         * @apiNote Blocking
         * @apiNote 0.4s duration
         */
        protected final RadarResults radarFull() {
            this.yield(new RadarCommand(5));
            return this.env.getRadar();
        }

        // Movement

        /**
         * Fires thrusters to accelerate the ship
         * @param dir Direction to accelerate in relative to the ship (x-forward, y-right)
         * @param dur Time to accelerate for
         * @param power Power to accelerate with (0.1-1)
         * @apiNote Blocking
         * @apiNote {@code [dur]}s duration
         */
        protected final void thrust(Direction dir, double dur, double power, boolean blocking) { this.thrustVectored(dir.vec(), dur, power, blocking); }

        protected final void boost(Direction dir, double time, double power, int boosts, boolean blocking) {
            for(int i = 0; i < Math.min(Math.max(boosts, 0), 3); i++) this.thrustVectored(dir.vec(), time, power, false);
            this.thrustVectored(dir.vec(), time, power, blocking);
        }

        /**
         * Fires thrusters to accelerate the ship, using vectored thrust (1 or 2 thrusters firing)
         * @param dir Direction to accelerate in relative to the ship (x-forward, y-right)
         * @param dur Time to accelerate for
         * @param power Power to accelerate with (0.1-1)
         * @apiNote Blocking
         * @apiNote {@code [dur]}s duration
         */
        protected final void thrustVectored(Vec dir, double dur, double power, boolean blocking) {
            dir = dir.normalize();

            double absx = Math.abs(dir.x);
            double absy = Math.abs(dir.y);

            if(absx >= 0.1) this.yield(new ThrustCommand(Direction.Forward.unsign(dir.x).thrust(), dur, power * absx, blocking && absy < 0.1));
            if(absy >= 0.1) this.yield(new ThrustCommand(Direction.Right.unsign(dir.y).thrust(), dur, power * absy, blocking));
        }

        /**
         * Fires thrusters to accelerate the ship, using vectored thrust (1 or 2 thrusters firing)
         * @param dir Direction to accelerate in relative to the world (x-right, y-down)
         * @param dur Time to accelerate for
         * @param power Power to accelerate with (0.1-1)
         * @apiNote Blocking
         * @apiNote {@code time}s duration
         */
        protected final void thrustVectoredWorld(Vec dir, double dur, double power, boolean blocking) { this.thrustVectored(dir.rotate(this.ang), dur, power, blocking); }

        protected final boolean rotate(int offset) {
            if(offset == 0) return false;
            this.yield(new RotateCommand(Utils.normalizeAngle(offset)));
            return true;
        }

        protected final boolean rotateTo(int angle) {
            return this.rotate(Utils.normalizeAngle(angle - this.ang));
        }

        protected final boolean face(Vec target) {
            return this.rotateTo((int)-Math.toDegrees(Math.atan2(target.y - this.pos.y, target.x - this.pos.x)));
        }

        protected final void brake() { this.brake(0); }

        protected final void brake(double percent) { this.yield(new BrakeCommand(percent)); }

        protected final void glide(Vec target, double maxSpeed, boolean faceTarget, double thrustDuration, double timeout) {
            Timeout tout = new Timeout(timeout);

            while(!tout.passed()) {
                Vec dir = target.sub(this.pos);
                double distance = dir.length();

                if(faceTarget) this.face(target);

                if(distance <= Utils.calculateDecelerationDistance(this.speed)) break;
                else if(this.speed < maxSpeed) {
                    if(faceTarget) this.thrust(Direction.Forward, thrustDuration, 1, false);
                    else this.thrustVectoredWorld(dir, thrustDuration, 1, false);
                } else this.idle(0.1);
            }

            this.brake();
        }

        protected final void glideContinuous(Vec target, double timeout, int boosts) {
            Timeout tout = new Timeout(timeout);

            this.face(target);
            this.boost(Direction.Forward, timeout, 1, boosts, false);

            while(!tout.passed()) {
                Vec dir = target.sub(this.pos);
                double distance = dir.length();

                if(distance <= Utils.calculateDecelerationDistance(this.speed)) break;
                else if(!this.face(target)) this.idle(0.1);
            }

            System.err.println("slow");

            this.cancel();
            this.brake();
            this.cancel();
        }

        protected final void glideBoost(Vec target, double maxSpeed, int boosts, double thrustDuration, double timeout) {
            Timeout tout = new Timeout(timeout);

            while(!tout.passed()) {
                Vec dir = target.sub(this.pos);
                double distance = dir.length();

                this.face(target);

                if(distance <= Utils.calculateDecelerationDistance(this.speed)) break;
                else if(this.speed < maxSpeed) this.boost(Direction.Forward, thrustDuration, 1, boosts, true);
                else this.idle(0.1);
            }

            this.brake();
        }

        protected final void steer(int ang, boolean blocking) {
            this.yield(new SteerCommand(ang, blocking));
        }

        protected final void steer(int ang) { this.steer(ang, true); }

        // Misc

        protected final void cancel() {
            this.steer(1, false);

            for(int i = 1; i < 3; i++) {
                this.steer(-1, false);
                this.steer(1, false);
            }

            this.steer(-1, true);
        }

        protected final void idle(double time) { this.yield(new IdleCommand(time)); }

        protected final void until(Supplier<Boolean> condition, double interval, double timeout) {
            Timeout tout = new Timeout(timeout);
            while(!(condition.get() || tout.passed())) this.idle(interval);
        }

        protected final <T> void untilChange(Supplier<T> value, double interval, double timeout) {
            T initial = value.get();
            this.until(() -> !value.get().equals(initial), interval, timeout);
        }

        protected final <T> void untilSufficientEnergy(double energy) {
            this.until(() -> this.energy >= energy, 0.1, 0);
        }
    }

    public static final class Utils {
        public static final int normalizeAngle(int angle) {
            angle = ((angle % 360) + 360) % 360;
            if(angle > 180) angle -= 360;
            return angle;
        }
        public static final double normalizeAngle(double angle) {
            angle = ((angle % 360) + 360) % 360;
            if(angle > 180) angle -= 360;
            return angle;
        }

        public static final double calculateDecelerationDistance(double velocity) {
            return (velocity * velocity) / (2 * Sim.ACCELERATION);
        }
    }

    // NCli

    private int width;
    private int height;

    private boolean ready = false;

    private final Map<Integer, ObjectStatus> celestials = new HashMap<>();
    private final ArrayList<Vec> pings = new ArrayList<>();
    private final ShipComputer ship;
    private final Thread coroutine;
    private final Sim sim;
    private final JFrame frame = new JFrame("NCli");
    private final JPanel canvas = new Panel();

    public NCli(String ip, ShipComputer ship) {
        ship.cli = this;

        this.canvas.setSize(1000, 1000);

        this.frame.add(this.canvas);
        this.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.frame.setVisible(true);
        this.frame.setSize(1000, 1000);
        this.frame.setResizable(false);
        try {
            this.frame.setIconImage(ImageIO.read(new File("icon.png")));
        } catch(IOException e) {
            e.printStackTrace();
        }

        this.ship = ship;

        this.coroutine = new Thread(ship::execute, "NCli:Coroutine");
        this.coroutine.setDaemon(true);
        this.coroutine.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable exception) {
                System.err.println("NCli:Coroutine Thread: uncaught exception:");
                exception.printStackTrace();
            }
        });
        this.coroutine.start();

        this.sim = new Sim();

        TextClient.run(ip, new BasicSpaceship() {
            @Override public RegistrationData registerShip(int numImages, int width, int height) {
                NCli.this.width = width;
                NCli.this.height = height;

                return ship.init(width, height);
            }

            @Override public ShipCommand getNextCommand(BasicEnvironment env) {
                synchronized(NCli.this.sim) {
                    NCli.this.sim.unblock();

                    ship.rx.fulfill(env);
                    ShipCommand cmd = ship.tx.await();

                    NCli.this.sim.cmd(cmd);
                    NCli.this.sim.update(env);
                    NCli.this.ready = true;

                    return cmd;
                }
            }

            @Override public void shipDestroyed(String by) {
                ship.destroyed(by);
            }
        });
    }
}
