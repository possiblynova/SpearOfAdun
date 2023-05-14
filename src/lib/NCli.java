package lib;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.IOException;
import java.util.function.Supplier;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;

import ihs.apcs.spacebattle.*;
import ihs.apcs.spacebattle.commands.*;

public class NCli {
    private static class Async<T> {
        private T value;
        private boolean flag = false;

        public synchronized void fulfill(T value) {
            this.flag = true;
            this.value = value;

            this.notifyAll();
        }

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
    }

    private static class Timeout {
        public final long end;

        public Timeout(double duration) { this.end = System.currentTimeMillis() + (long)(duration * 1000); }

        public boolean passed() { return System.currentTimeMillis() >= this.end; }
    }

    public static class Vec {
        public double x;
        public double y;

        public Vec(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public Vec(double n) { this(n, n); }

        public Vec(Point pt) { this(pt.getX(), pt.getY()); }

        public Vec polar(int angle, double magnitude) {
            double radians = Math.toRadians(angle);

            return new Vec(Math.cos(radians) * magnitude, Math.sin(radians) * magnitude);
        }

        public Point point() { return new Point(this.x, this.y); }

        public Vec add(Vec rhs) { return new Vec(this.x + rhs.x, this.y + rhs.y); }
        public Vec sub(Vec rhs) { return new Vec(this.x - rhs.x, this.y - rhs.y); }
        public Vec mul(Vec rhs) { return new Vec(this.x * rhs.x, this.y * rhs.y); }
        public Vec div(Vec rhs) { return new Vec(this.x / rhs.x, this.y / rhs.y); }

        public double length2() { return Math.pow(this.x, 2) + Math.pow(this.y, 2); }
        public double length() { return Math.sqrt(this.length2()); }

        public Vec normalize() {
            return this.div(new Vec(this.length()));
        }

        public double dist2(Vec rhs) { return Math.pow(this.x - rhs.x, 2) + Math.pow(this.y - rhs.y, 2); }
        public double dist(Vec rhs) { return Math.sqrt(this.dist2(rhs)); }

        public Vec rotate(int angle) {
            double radians = Math.toRadians(angle);

            return new Vec(
                (Math.cos(radians) * this.x) - (Math.sin(radians) * this.y),
                (Math.sin(radians) * this.x) + (Math.cos(radians) * this.y)
            );
        }

        public Vec swap() { return new Vec(this.y, this.x); }

        @Override
        public String toString() {
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

        final Async<ShipCommand> tx = new Async<>();
        final Async<BasicEnvironment> rx = new Async<>();

        final void execute() {
            this.env = this.rx.await();
            this.updateStatus();
            this.run();
            this.tx.fulfill(null);
        }

        final void updateStatus() {
            this.ship = this.env.getShipStatus();
            this.pos = new Vec(this.ship.getPosition());
            this.ang = Utils.normalizeAngle(this.ship.getOrientation());
            this.speed = this.ship.getSpeed();
            this.vel = new Vec(this.ship.getMovementDirection(), this.speed);
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
        protected Vec pos;
        protected int ang;
        protected double speed;
        protected Vec vel;

        /**
         * Yields a command to the server
         * @param cmd The command to yield, will block if the command is blocking
         */
        protected final void yield(ShipCommand cmd) {
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

        protected final void thrust(Direction dir, double time, double power) { this.thrust(dir.vec(), time, power); }

        protected final void thrust(Vec dir, double time, double power) {
            dir = dir.normalize();

            double absx = Math.abs(dir.x);
            double absy = Math.abs(dir.y);

            if(absx >= 0.1) this.yield(new ThrustCommand(Direction.Forward.unsign(dir.x).thrust(), time, power * absx, absy < 0.1));
            if(absy >= 0.1) this.yield(new ThrustCommand(Direction.Right.unsign(dir.y).thrust(), time, power * absy, true));
        }

        protected final void thrustGlobal(Vec dir, double time, double power) { this.thrust(dir.rotate(this.ang), time, power); }

        protected final void rotate(int offset) {
            if(offset == 0) return;
            this.yield(new RotateCommand(offset));
        }

        protected final void rotateTo(int angle) {
            this.rotate(Utils.normalizeAngle(angle - this.ang));
        }

        protected final void face(Vec target) {
            this.rotateTo((int)-Math.toDegrees(Math.atan2(target.y - this.pos.y, target.x - this.pos.x)));
        }

        protected final void brake() { this.brake(0); }

        protected final void brake(double percent) { this.yield(new BrakeCommand(percent)); }

        protected final void glide(Vec target, double maxSpeed, boolean faceTarget, double thrustDuration, double timeout) {
            Timeout tout = new Timeout(timeout);

            while(!tout.passed()) {
                Vec dir = target.sub(this.pos);
                double distance = dir.length();

                this.face(target);

                if(distance <= Utils.calculateDecelerationDistance(this.speed)) break;
                else if(this.speed < maxSpeed) {
                    if(faceTarget) this.thrust(Direction.Forward, thrustDuration, 1);
                    else this.thrustGlobal(dir, thrustDuration, 1);
                } else this.idle(0.01);
            }

            this.brake();
        }

        // Misc

        protected final void idle(double time) { this.yield(new IdleCommand(time)); }

        protected final void until(Supplier<Boolean> condition, double interval, double timeout) {
            Timeout tout = new Timeout(timeout);
            while(!(condition.get() || tout.passed())) this.idle(interval);
        }

        protected final <T> void untilChange(Supplier<T> value, double interval, double timeout) {
            T initial = value.get();
            this.until(() -> !value.get().equals(initial), interval, timeout);
        }
    }

    public static class Utils {
        public static final double acceleration = 6.6;

        public static final int normalizeAngle(int angle) {
            angle = ((angle % 360) + 360) % 360;
            if(angle > 180) angle -= 360;
            return angle;
        }

        public static final double calculateDecelerationDistance(double velocity) {
            return (velocity * (velocity / Utils.acceleration)) / 2;
        }
    }

    private class Sim {
        public Vec pos;
        public int ang;
        public double speed;
        public Vec vel;

        public ShipCommand[] commands;

        public void update(BasicEnvironment env) {
            ObjectStatus ss = env.getShipStatus();
            this.pos = new Vec(ss.getPosition());
            this.ang = ss.getOrientation();
            this.speed = ss.getSpeed();
            this.vel = new Vec(ss.getMovementDirection(), this.speed);
        }
    }

    // NCli

    private final Thread coroutine;
    private final ShipComputer ship;
    private final Sim sim = new Sim();
    private final JFrame frame = new JFrame("NCli");
    private final JPanel canvas = new JPanel() {
        private Polygon tri = new Polygon(new int[] { -1, 1, -1 }, new int[] { -1, 0, 1 }, 3);

        @Override
        public void paint(Graphics ctxi) {
            Graphics2D ctx = (Graphics2D)ctxi;

            int width = this.getWidth();
            int height = this.getHeight();

            ctx.clearRect(0, 0, width, height);

            this.trans(ctx, AffineTransform.getTranslateInstance(16, 16), () -> {
                this.indicator(ctx, "Velocity: %.1f".formatted(NCli.this.ship.speed, NCli.this.ship.ang), NCli.this.ship.vel);
            });

            this.trans(ctx, AffineTransform.getTranslateInstance(16, 16 + 32 + 16), () -> {
                this.indicator(ctx, "Accel".formatted(NCli.this.ship.speed, NCli.this.ship.ang), NCli.this.ship.vel);
            });
        }

        private void indicator(Graphics2D ctx, String label, Vec value) {
            ctx.draw3DRect(0, 0, 128, 128, false);
            ctx.drawString(label, 4, 8);

            ctx.setColor(new Color(255, 0, 0));

            AffineTransform shipTransform = new AffineTransform();
            shipTransform.translate(64, 64);
            shipTransform.scale(4, 4);
            shipTransform.rotate(Math.toRadians(-NCli.this.ship.ang));
            this.trans(ctx, shipTransform, () -> {
                this.ship(ctx);
            });
        }

        private void ship(Graphics2D ctx) {
            ctx.drawPolygon(this.tri);
        }

        private void trans(Graphics2D ctx, AffineTransform trans, Runnable in) {
            AffineTransform old = ((Graphics2D)ctx).getTransform();
            ctx.transform(trans);
            in.run();
            ctx.setTransform(old);
        }
    };

    {
        this.canvas.setSize(1000, 750);

        this.frame.add(this.canvas);
        this.frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.frame.setVisible(true);
        this.frame.setSize(1000, 750);
        try { this.frame.setIconImage(ImageIO.read(new File("icon.png"))); } catch(IOException e) { e.printStackTrace(); };
    }

    public NCli(String ip, ShipComputer ship) {
        this.ship = ship;

        this.coroutine = new Thread(ship::execute, "ShipComputer");
        this.coroutine.setDaemon(true);
        this.coroutine.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable exception) {
                System.err.println("ShipComputer thread: uncaught exception:");
                exception.printStackTrace();
            }
        });
        this.coroutine.start();

        TextClient.run(ip, new BasicSpaceship() {
            @Override
            public RegistrationData registerShip(int numImages, int width, int height) {
                return ship.init(width, height);
            }

            @Override
            public ShipCommand getNextCommand(BasicEnvironment env) {
                ship.rx.fulfill(env);
                canvas.revalidate();
                canvas.repaint();
                return ship.tx.await();
            }

            @Override
            public void shipDestroyed(String by) {
                ship.destroyed(by);
            }
        });
    }
}
