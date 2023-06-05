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
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.event.WindowListener;
import java.awt.event.WindowStateListener;
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
            } catch(final InterruptedException ex) {
                throw new Error(ex);
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
        public synchronized void fulfill(final T value) {
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
        public Timeout(final double duration) {
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
    public final class Sim {
        /**
         * A ShipCommand converted into a simulated command
         */
        abstract class SimCommand {
            /**
             * Whether
             */
            boolean initial = true;

            double fullTime;

            void once() {}
            boolean isFinished() { return true; }
            void execute() {}

            double getInstantEnergyCost() { return 0; }
            double getOngoingEnergyCost() { return 0; }

            boolean blocking() { return true; }

            abstract String name();
            abstract double timeRemaining();

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

            @Override String name() { return "Rotate"; }
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
                Sim.this.vel = Sim.this.vel.add(this.dir.vec().scale(this.power * Sim.ACCELERATION * Sim.TIME).rotate(-Sim.this.ang));
                this.duration -= Sim.TIME;
            }

            @Override double getOngoingEnergyCost() { return 3 * this.power; }

            @Override boolean blocking() { return this.blocking; }

            @Override String name() { return "Thrust"; }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        class SimBrakeCommand extends SimCommand {
            final double target;

            public SimBrakeCommand(final BrakeCommand cmd) {
                this.target = (Double)this.rgp(cmd, "PER");
            }

            @Override boolean isFinished() { return Sim.this.vel.length2() <= this.target; }
            @Override void execute() {
                Sim.this.vel = Sim.this.vel.withLength(Math.max(Sim.this.vel.length() - Sim.ACCELERATION * Sim.TIME, this.target));
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return true; }

            @Override String name() { return "Brake"; }
            @Override double timeRemaining() { return Math.max((Sim.this.vel.length() - this.target) / Sim.ACCELERATION, 0); }
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
                    amt = -Sim.TURN_RATE * Sim.TIME;
                    if(amt < this.deg) amt = this.deg;
                } else {
                    amt = Sim.TURN_RATE * Sim.TIME;
                    if(amt > this.deg) amt = this.deg;
                }
                this.deg -= amt;

                Sim.this.vel = Sim.this.vel.rotate(amt);
            }

            @Override double getOngoingEnergyCost() { return 4; }

            @Override boolean blocking() { return this.blocking; }

            @Override String name() { return "Steer"; }
            @Override double timeRemaining() { return Math.abs(this.deg / Sim.TURN_RATE); }
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

            @Override String name() { return "Idle"; }
            @Override double timeRemaining() { return Math.max(this.duration, 0); }
        }

        /**
         * The minimum time per game tick
         * @implSpec Should be derived from {@link https://github.com/Mikeware/SpaceBattleArena/blob/master/SBA_Serv/World/WorldMap.py#L24}
         */
        public static final double TIME = 1.0 / 30.0;

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

        // Non-simulated Stats
        double lastScore = 0;

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
            this.lastScore = env.getGameInfo().getScore();
        }

        synchronized void cmd(final ShipCommand cmd) {
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
        private final static Polygon tri = new Polygon(new int[] { -4, 4, 16, 4, -4 }, new int[] { -4, 4, 0, -4, 4 }, 5);

        private final static Font font = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        private final static Font fontBig = new Font(Font.MONOSPACED, Font.PLAIN, 32);

        private final static Color background = new Color(16, 16, 16);
        private final static Color frameBackground = new Color(64, 64, 64);
        private final static Color frameGrid = new Color(32, 32, 32);

        private final static Color foreground = new Color(255, 255, 255);
        private final static Color shipIndicator = new Color(96, 96, 96);

        private final static Color red = new Color(255, 64, 64);
        private final static Color yellow = new Color(255, 255, 64);
        private final static Color green = new Color(64, 255, 64);

        private Graphics2D render;

        private final Rectangle vPanelBounds = new Rectangle();
        private final Rectangle mapBounds = new Rectangle();

        private Point click = new Point(0, 0);

        @Override public void paint(final Graphics graphics) {
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
            final String[] lines = NCli.this.sim.status.split("\n");
            for(int i = 0; i < lines.length; i++) this.center(lines[i], width / 2, 128 + 12 * i);

            // Stats

            this.trans(AffineTransform.getTranslateInstance(width / 2, 0), () -> {
                this.render.setColor(Panel.frameBackground);
                this.render.fill3DRect(-256, 16, 512, 16, false);

                this.render.setColor(Panel.yellow);
                this.render.fillRect(-256 + 4, 16 + 4, (int)((512 - 8) * (NCli.this.sim.energy / 100)), 16 - 8);

                this.center("%.1f%%".formatted(NCli.this.sim.energy), 0, 64 - 16);

                final int net = (int)((-NCli.this.sim.consumedThisTick / Sim.TIME) + 4);

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
                for(int i = 0; i < NCli.this.sim.commands.size(); i++) {
                    final Sim.SimCommand cmd = NCli.this.sim.commands.get(i);
                    this.frame(16, 16 + 64 * i, 256, 64, false, (final Integer sw, final Integer sh) -> {
                        this.render.setColor(Panel.foreground);

                        if(cmd instanceof final Sim.SimRotateCommand rcmd) {
                            this.render.drawArc(sw / 2 - 16, sh / 2 - 16, 32, 32, (int)(NCli.this.sim.ang), (int)(rcmd.deg));
                            this.render.setColor(Panel.shipIndicator);
                            this.ship(false, sw / 2, sh / 2, 16);
                        } else if(cmd instanceof final Sim.SimSteerCommand rcmd) {
                            this.render.drawArc(sw / 2 - 16, sh / 2 - 16, 32, 32, (int)(NCli.this.sim.ang), (int)(rcmd.deg));
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
            if(aspect > 1) this.mapBounds.setBounds(16, height - 16 - 256, (int)(256 * aspect), 256);
            else this.mapBounds.setBounds(16, height - 16 - 256, (int)(256 * aspect), 256);
            this.frame(this.mapBounds, (final Integer w, final Integer h) -> {
                this.render.setColor(Panel.frameGrid);
                this.render.drawLine(w / 2, 0, w / 2, h);
                this.render.drawLine(0, h / 2, w, h / 2);

                this.render.setColor(Panel.foreground);
                // this.ship(
                //     true,
                //     (int)this.mapToPx(NCli.this.sim.pos.x),
                //     (int)this.mapToPx(NCli.this.sim.pos.y),
                //     this.mapToPx(28)
                // );

                final AffineTransform trans = new AffineTransform();
                trans.scale(this.mapBounds.width / NCli.this.size.x, this.mapBounds.height / NCli.this.size.y); // should be proportional but whatever
                this.trans(trans, () -> {
                    this.ship(
                        true,
                        (int)NCli.this.sim.pos.x,
                        (int)NCli.this.sim.pos.y,
                        28
                    );

                    NCli.this.ship.mapPaint(this.render);
                });

                NCli.this.ship.mapOverlayPaint(this.render);
                // final AffineTransform shipTransform = new AffineTransform();
                // shipTransform.translate(256 * aspect * (NCli.this.sim.pos.x / NCli.this.size.x), 256 * (NCli.this.sim.pos.y / NCli.this.size.y));
                // shipTransform.rotate(Math.toRadians(-NCli.this.sim.ang));
                // this.trans(shipTransform, () -> this.ship());
            });

            // Indicator

            this.indicator(16, height - 16 - 256 - 16 - 256, 256, "Velocity: %.1f".formatted(NCli.this.sim.vel.length()), NCli.this.sim.vel, 100);

            // Ship

            this.render.setColor(Panel.foreground);

            this.ship(false, width / 2, height / 2, 64);

            this.render.drawRect((int)this.click.getX() - 1, (int)this.click.getY() - 1, 2, 2);
        }

        private void indicator(final int x, final int y, final int size, final String label, final Vec value, final double max) {
            this.frame(x, y, size, size, (w, h) -> {
                this.render.setColor(Panel.frameGrid);
                this.render.drawLine(w / 2, 0, w / 2, h);
                this.render.drawLine(0, h / 2, w, h / 2);

                this.render.setColor(Panel.foreground);
                this.render.drawString(label, 4, 16);

                this.render.setColor(Panel.shipIndicator);
                this.ship(false, size / 2, size / 2, this.mapToPx(max / 28));

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
            shipTransform.rotate(Math.toRadians(-NCli.this.sim.ang));
            shipTransform.scale(size / 20, size / 20);
            this.trans(shipTransform, () -> {
                this.render.drawPolygon(Panel.tri);
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

        private double pxToMap(final double map) {
            return map * (NCli.this.size.x / this.mapBounds.width);
        }

        // Input

        private boolean inputValid(final InputEvent evt) {
            if(!NCli.this.ready) return false;
            return (
                evt instanceof MouseEvent
                && this.mapBounds.contains(((MouseEvent)evt).getPoint())
            );
        }

        @Override public void mouseWheelMoved(final MouseWheelEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
        @Override public void mouseDragged(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
        @Override public void mouseMoved(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
        @Override public void mouseClicked(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
        }
        @Override public void mousePressed(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;

            this.click = evt.getPoint();

            System.out.println(new Vec(this.pxToMap(evt.getX() - this.mapBounds.x), this.pxToMap(evt.getY() - this.mapBounds.y)));
        }
        @Override public void mouseReleased(final MouseEvent evt) {
            evt.consume();
            if(!NCli.this.ready) return;
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
                if(evt.getKeyCode() == KeyEvent.VK_SPACE) NCli.this.start.fulfill(null);
                return;
            }
        }

        @Override public void keyReleased(final KeyEvent evt) {
            evt.consume();
        }
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

        public double length2() { return Math.pow(this.x, 2) + Math.pow(this.y, 2); }
        public double length() { return Math.sqrt(this.length2()); }

        public Vec normalize() {
            return this.div(new Vec(this.length()));
        }

        public Vec withLength(final double magnitude) { return this.normalize().scale(magnitude); }

        public double dist2(final Vec rhs) { return Math.pow(this.x - rhs.x, 2) + Math.pow(this.y - rhs.y, 2); }
        public double dist(final Vec rhs) { return Math.sqrt(this.dist2(rhs)); }

        public Vec rotate(final double deg) {
            final double radians = Math.toRadians(deg);

            return new Vec(
                (Math.cos(radians) * this.x) - (Math.sin(radians) * this.y),
                (Math.sin(radians) * this.x) + (Math.cos(radians) * this.y)
            );
        }
        public Vec rotate(final Rotation angle) { return this.rotate(angle.deg); }

        public double angle() { return -Math.toDegrees(Math.atan2(this.y, this.x)); }

        public Vec swap() { return new Vec(this.y, this.x); }

        @Override public String toString() {
            return "<%f, %f>".formatted(this.x, this.y);
        }
    }

    public record Rotation(double deg) {
        public Rotation(final double deg) { this.deg = Utils.normalizeAngle(deg); }
        public Rotation() { this(0); }

        public static Rotation deg(final double deg) { return new Rotation(deg); }
        public static Rotation rad(final double rad) { return new Rotation(Math.toDegrees(rad)); }

        public double deg() { return this.deg; }
        public double rad() { return Math.toRadians(this.deg); }

        public Rotation neg() { return new Rotation(-this.deg); }
        public Rotation abs() { return new Rotation(Math.abs(this.deg)); }

        public Rotation add(final Rotation rhs) { return new Rotation(this.deg + rhs.deg); }
        public Rotation sub(final Rotation rhs) { return new Rotation(this.deg - rhs.deg); }
        public Rotation mul(final Rotation rhs) { return new Rotation(this.deg * rhs.deg); }
        public Rotation div(final Rotation rhs) { return new Rotation(this.deg / rhs.deg); }

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

    public static abstract class ShipComputer {
        // Internals

        NCli cli;
        final Async<ShipCommand> tx = new Async<>();
        final Async<BasicEnvironment> rx = new Async<>();
        final boolean vPanelEnabled;

        {
            boolean en = true;
            try {
                this.getClass().getDeclaredMethod("vPanelPaint", this.getClass(), Graphics2D.class);
            } catch(final NoSuchMethodException ex) {
                en = false;
            } catch(final Exception ex) {
                throw new Error(ex);
            }
            this.vPanelEnabled = en;
        }

        final void execute() {
            this.env = this.rx.await();
            this.updateStatus();
            this.run();
            while(true) this.idle(1);
        }

        final void updateStatus() {
            this.ship = this.env.getShipStatus();
            this.pos = new Vec(this.ship.getPosition());
            this.ang = new Rotation(this.ship.getOrientation());
            this.speed = this.ship.getSpeed();
            this.vel = Vec.polar(this.ship.getMovementDirection(), this.speed);

            this.health = this.ship.getHealth();
            this.shield = this.ship.getShieldLevel();
            this.energy = this.ship.getEnergy();
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

        protected void mapPaint(final Graphics2D render) {}
        protected void mapOverlayPaint(final Graphics2D render) {}

        protected void mapClick(final int x, final int y) {}
        protected void mapRightClick(final int x, final int y) {}
        protected void mapMouseMove(final int x, final int y) {}

        // Overrides - vPanel

        protected void vPanelPaint(final Graphics2D render) {}

        protected void vPanelClick(final int x, final int y) {}
        protected void vPanelRightClick(final int x, final int y) {}
        protected void vPanelMouseMove(final int x, final int y) {}

        // Utiltiies

        protected BasicEnvironment env;
        protected ObjectStatus ship;
        protected Vec pos = new Vec(0, 0);
        protected Rotation ang = new Rotation();
        protected double speed = 0;
        protected Vec vel = new Vec(0, 0);
        protected double health = 0;
        protected double shield = 0;
        protected double energy = 0;

        protected final void status(final String status) {
            this.cli.sim.status = status;
        }

        /**
         * Yields a command to the server
         * @param cmd The command to yield, will block if the command is blocking
         */
        private final void yield(final ShipCommand cmd) {
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
         * @apiNote 0.1s duration
         */
        protected final ObjectStatus radarTarget(final int target) {
            this.yield(new RadarCommand(3, target));
            final RadarResults res = this.env.getRadar();
            return res != null ? res.get(0) : null;
        }

        /**
         * Pings the radar for slightly less limited information on nearby objects
         *
         * @return The ID, position, and type of nearby objects
         * @apiNote Equivalent to an L4 scan
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
         */
        protected final void thrust(final Direction dir, final double dur, final double power, final boolean blocking) { this.thrustVectored(dir.vec(), dur, power, blocking); }

        protected final void boost(final Direction dir, final double time, final double power, final int boosts, final boolean blocking) {
            for(int i = 0; i < Math.min(Math.max(boosts, 0), 3); i++) this.thrustVectored(dir.vec(), time, power, false);
            this.thrustVectored(dir.vec(), time, power, blocking);
        }

        /**
         * Fires thrusters to accelerate the ship, using vectored thrust (1 or 2 thrusters firing)
         * @param dir Direction to accelerate in relative to the ship (x-forward, y-right)
         * @param dur Time to accelerate for
         * @param power Power to accelerate with (0.1-1)
         */
        protected final void thrustVectored(Vec dir, final double dur, final double power, final boolean blocking) {
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
        protected final void thrustVectoredWorld(final Vec dir, final double dur, final double power, final boolean blocking) { this.thrustVectored(dir.rotate(this.ang), dur, power, blocking); }

        protected final boolean rotate(final Rotation offset) {
            if((int)offset.deg == 0) return false;
            this.yield(new RotateCommand((int)offset.deg));
            return true;
        }

        protected final boolean rotateTo(final Rotation angle) {
            return this.rotate(angle.sub(this.ang));
        }

        protected final boolean face(final Vec target) {
            return this.rotateTo(Utils.calculateAngleTo(this.pos, target));
        }

        protected final void brake() { this.brake(0); }

        protected final void brake(final double percent) { this.yield(new BrakeCommand(percent)); }

        protected final void glide(final Vec target, final double maxSpeed, final boolean faceTarget, final double thrustDuration, final double timeout) {
            final Timeout tout = new Timeout(timeout);

            while(!tout.passed()) {
                final Vec dir = target.sub(this.pos);
                final double distance = dir.length();

                if(faceTarget) this.face(target);

                if(distance <= Utils.calculateDecelerationDistance(this.speed)) break;
                else if(this.speed < maxSpeed) {
                    if(faceTarget) this.thrust(Direction.Forward, thrustDuration, 1, false);
                    else {
                        this.thrustVectoredWorld(dir, thrustDuration, 1, false);
                    }
                } else this.idle(0.1);

                this.steerToFace(target, true);
            }

            this.brake();
        }

        protected final void glideBoost(final Vec target, final double maxSpeed, final int boosts, final double thrustDuration, final double timeout) {
            final Timeout tout = new Timeout(timeout);

            while(!tout.passed()) {
                final Vec dir = target.sub(this.pos);
                final double distance = dir.length();

                this.face(target);

                if(distance <= Utils.calculateDecelerationDistance(this.speed, Sim.ACCELERATION * 2)) break;
                else if(this.speed < maxSpeed) this.boost(Direction.Forward, thrustDuration, 1, boosts, true);
                else this.idle(0.1);

                this.steerToFace(target, true);
            }

            //this.brake();

            this.boost(Direction.Back, this.vel.length() / (Sim.ACCELERATION * 2), 1, boosts, true);
        }

        protected final boolean steer(final int offset, final boolean blocking) {
            if(offset == 0) return false;
            this.yield(new SteerCommand(offset, blocking));
            return true;
        }

        protected final boolean steerTo(final int angle, final boolean blocking) {
            return this.steer(Utils.normalizeAngle(angle - (int)this.vel.angle()), blocking);
        }

        protected final boolean steerToFace(final Vec target, final boolean blocking) {
            return this.steerTo((int)-Math.toDegrees(Math.atan2(target.y - this.pos.y, target.x - this.pos.x)), blocking);
        }

        // Torpedo

        protected final void fire(final Direction dir) {
            this.yield(new FireTorpedoCommand(dir.torp()));
        }

        // Misc

        protected final void cancel() {
            for(int i = 0; i < 1; i++) {
                for(int j = 0; j < 3; j++) {
                    this.steer(-1, false);
                    this.steer(1, j == 2);
                }
            }
        }

        protected final void idle(final double time) { this.yield(new IdleCommand(time)); }

        protected final void until(final Supplier<Boolean> condition, final double interval, final double timeout) {
            final Timeout tout = new Timeout(timeout);
            while(!(condition.get() || tout.passed())) this.idle(interval);
        }

        protected final <T> void untilChange(final Supplier<T> value, final double interval, final double timeout) {
            final T initial = value.get();
            this.until(() -> !value.get().equals(initial), interval, timeout);
        }

        protected final <T> void untilSufficientEnergy(final double energy) {
            this.until(() -> this.energy >= energy, 0.1, 0);
        }
    }

    public final class RadarSystem {
        public record Celestial(
            Vec position,
            Vec size,
            double influenceRadius,
            double influence
        ) {
        }

        private final ArrayList<ObjectStatus> celestials = new ArrayList<ObjectStatus>();
        private final ArrayList<Vec> pingOrigins = new ArrayList<>();

        final void radar(final RadarResults res, final int level) {
            for(final ObjectStatus object : res) {
                // todo: this
            }
        }
    }

    public static final class Utils {
        public static final double remap(final double value, final double fromMin, final double fromMax, final double toMin, final double toMax) {
            return toMin + (value - fromMin) * (toMax - toMin) / (fromMax - fromMin);
        }

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

        public static final Rotation calculateAngleTo(final Vec src, final Vec dst) {
            return Rotation.rad(-Math.atan2(dst.y - src.y, dst.x - src.x));
        }

        public static final double calculateDecelerationDistance(final double velocity) {
            return Utils.calculateDecelerationDistance(velocity, Sim.ACCELERATION);
        }

        public static final double calculateDecelerationDistance(final double velocity, final double acceleration) {
            return (velocity * velocity) / (2 * acceleration);
        }

        public static final Vec calculateInterceptPosition(
            final Vec pos,
            final Vec vel,
            final Vec targetPos,
            final Vec targetVel,
            final double projSpeed
        ) {
            // todo: figure out how to handle self velocity being nonzero
            //assert vel.length() < 1;

            // inputs
            final Vec P1 = pos;
            final Vec P2 = targetPos;
            final Vec vc = targetVel;
            final double vp = projSpeed;

            // calculations
            final Vec c0 = P2.sub(P1);

            final double a0 = c0.normalize().dot(vc);
            final double b = Math.sqrt(Math.pow(c0.length(), 2) - Math.pow(a0, 2));

            final double qa = vc.length2() - Math.pow(vp, 2);
            final double qb = 2 * a0 * vc.length();
            final double qc = Math.pow(a0, 2) + Math.pow(b, 2);
            final double th = (-qb + Math.sqrt(Math.pow(qb, 2) - 4 * qa * qc)) / (2 * qa);

            //System.out.printf("---\nc0=%s\nvc=%s\na0=%f\nb=%f\nqa=%f\nqb=%f\nqc=%f\nth=%f\nP3=%s\n", c0, vc, a0, b, qa, qb, qc, th, P2.add(vc.scale(th)));

            return P2.add(vc.scale(-th * 1.5));
        }
    }

    // NCli

    private Vec size;

    private boolean ready = false;
    private final Async<Void> start = new Async<>();

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

        this.coroutine = new Thread(ship::execute, "NCli:Coroutine");
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

                    ship.rx.fulfill(env);
                    final ShipCommand cmd = ship.tx.await();

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
