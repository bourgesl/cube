/*
 *    This file is part of Cubes.
 *
 *    Cubes is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    any later version.
 *
 *    Cubes is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with Cubes.  If not, see <http://www.gnu.org/licenses/>.
 */
package screen;

import java.awt.Color;
import java.awt.DisplayMode;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * A fullscreen of rotating cubes painted by Java2d.
 * The goal is to test the new rendering engine called Marlin in the 
 * coming Jdk 9.
 * 
 * How to use:
 * -----------
 * 
 * java -DthreadCount=8 -DcubeSize=64 screen.Cubes
 * threadCount is optional : the number of drawing threads including main
 *    thread rendering the screen. Default is 8.
 * cubeSize is optional : the width of each square area of each cube.
 *    Default is 64.
 * 
 * Press the 'Esc' key to quit early.
 * 
 * The number of cubes depends on the resolution and the size of the area 
 * containing a cube. 
 * For example in HD (1920*1024) with a CUBE_SIZE=64, there will be 
 * (1920/64)*(1024/64) = 480 cubes on screen.
 * With at more 3 visible sides by cube, it means 480*3=1440 polygons 
 * of 4 points.
 *
 * Other values examples :
 * CUBE_SIZE=256 => 84 sides to draw
 * CUBE_SIZE=16 => 24120 sides to draw
 * 
 * Rotation and projection are manually done ("pour le plaisir").
 * 
 * The example manages multi or mono thread rendering.
 * To activate multithread, change the static attribute THREADS_COUNT:
 * - a value of 1 or less : the mono thread mode is enabled
 * - a value of 2 or more : the multi thread mode is enabled with 
 * THREADS_COUNT-1 threads drawing the cubes.
 * 
 * The number of FPS appears in the top left corner of the screen. When
 * the color is red, the frame is drawn at a rate less the 60 fps.
 * 
 * Aghnar / Temsoft 
 */
public final class Cubes extends FullScreen {

    private static final boolean SKIP_RENDER = false;

    public static final boolean USE_GRAPHICS_ACCELERATION = true;

    // Add this to change the renderer
    //-Xbootclasspath/a:[full path]/marlin-X.Y.jar -Dsun.java2d.renderer=org.marlin.pisces.PiscesRenderingEngine	
    //-Xbootclasspath/a:E:/projets/pixelGiant/new-tuesday/new-tuesday/distribution/marlin-X.Y.jar -Dsun.java2d.renderer=org.marlin.pisces.PiscesRenderingEngine
    /* THREADS_COUNT>=2 enables multi thread rendering */
    private static final int THREADS_COUNT = getInteger("threadCount", 4);

    /* Rectangular area of CUBE_SIZE width containing a cube */
    private static final int CUBE_SIZE = getInteger("cubeSize", 64);		// try 32,16,128...

    /* A cube to be rendered by Java2D... */
    private static final class Cube {
        // static structure of a cube : points, sides (defined in the 
        // trigonometric order) and colors

        static final double[] points = {-16, 16, 16, -16, -16, 16, 16, -16, 16, 16, 16, 16,
                                        -16, 16, -16, -16, -16, -16, 16, -16, -16, 16, 16, -16};
        static final int[] f = {0, 1, 2, 3, 1, 5, 6, 2, 2, 6, 7, 3, 4, 7, 6, 5, 0, 3, 7, 4, 1, 0, 4, 5};
        static final Color[] fcol = new Color[6];

        static {
            fcol[0] = new Color(67, 130, 178);
            fcol[1] = new Color(175, 228, 232);
            fcol[2] = new Color(27, 90, 138);
            fcol[3] = new Color(67, 130, 178);
            fcol[4] = new Color(127, 190, 238);
            fcol[5] = new Color(24, 77, 175);
        }
        // Each instance current coordinates
        final double[] x = new double[8];      // 3d
        final double[] y = new double[8];
        final double[] z = new double[8];
        final double[] xe = new double[8];     // Projection
        final double[] ye = new double[8];
        double cx, cy;                   // Position on screen

        final Path2D.Float[] sides;
        final boolean[] visible;

        Cube(double cx, double cy) {
            int j = 0;
            for (int i = 0; i < 8; i++) {
                x[i] = 8 * points[j++];
                y[i] = 8 * points[j++];
                z[i] = 8 * points[j++];
                xe[i] = 0;
                ye[i] = 0;
            }
            this.cx = cx;
            this.cy = cy;
            sides = new Path2D.Float[6];
            visible = new boolean[6];

            for (int i = 0; i < 6; i++) {
                sides[i] = new Path2D.Float();
            }
        }
    }

    /* static trigonometric table initialized with precision 1/8 degree */
    public static final int ANG_PREC = 8;
    public static final int ANG_INC = 4;
    private static final int ANG_SIZE = 360 * ANG_PREC;
    private static final double[] cos = new double[ANG_SIZE];
    private static final double[] sin = new double[ANG_SIZE];

    static {
        for (int i = 0; i < ANG_SIZE; i++) {
            cos[i] = Math.cos((2 * i) * Math.PI / ANG_SIZE);
            sin[i] = Math.sin((2 * i) * Math.PI / ANG_SIZE);
        }
    }

    static int getInteger(final String key, final int def) {
        final String property = System.getProperty(key);
        if (property != null) {
            try {
                return Integer.parseInt(property);
            } catch (NumberFormatException e) {
                System.out.println("Invalid integer value for " + key + " = " + property);
            }
        }
        return def;
    }

    /* Rendering thread in multi thread mode. Simple : each thread
	 * has its own range of cubes to draw. Not the best of course...
	 * (to optimize, we could use a common queue or apply 
	 * jdk 7+ fork/join pool)
     */
    private final class Drawer implements Runnable {

        private final int start;
        private final int end;

        Drawer(int idx, int end) {
            this.start = idx;
            this.end = end;
        }

        @Override
        public void run() {
            for (int i = start, len = end; i < len; i++) {
                drawCube(i);
            }
        }
    }

    private int cubesCount = 0;
    private final int width;
    private final int height;
    private final BufferedImage img;
    private final Graphics2D gi;
    private final Graphics2D[] tiles;
    private int ang = 0;
    private final Cube cube;

    private final ThreadPoolExecutor pool;
    private final ArrayList<Future<?>> futures;
    private final Drawer[] drawers;

    // Measures
    private long minFPS = Long.MAX_VALUE;
    private long maxFPS = Long.MIN_VALUE;
    private long averageFPS = 0;
    private long frameCount = 0;

    // Let's rotate...
    public static void main(String[] a) {
        System.out.println("Number of threads " + THREADS_COUNT);
        System.out.println("Each cube area side " + CUBE_SIZE);

        new Cubes().start();
    }

    /**
     * Constructor initializes our cubes and threads pool if necessary 
     */
    public Cubes() {
        super();

        final DisplayMode displayMode = currentScreenDevice.getDisplayMode();

        width = displayMode.getWidth();
        height = displayMode.getHeight();

        final int xCount = width / CUBE_SIZE;
        final int yCount = height / CUBE_SIZE;

        cubesCount = xCount * yCount;

        cube = new Cube(CUBE_SIZE / 2, CUBE_SIZE / 2);

        if (THREADS_COUNT > 1) {
            pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(THREADS_COUNT);
            pool.prestartAllCoreThreads();
            futures = new ArrayList<>(THREADS_COUNT);

            drawers = new Drawer[THREADS_COUNT];

            final int p = cubesCount / THREADS_COUNT;
            for (int i = 0; i < THREADS_COUNT; i++) {
                drawers[i] = (i == THREADS_COUNT - 1) ? new Drawer(i * p, cubesCount) : new Drawer(i * p, i * p + p);
            }
        } else {
            pool = null;
            futures = null;
            drawers = null;
        }

        // We use a bufferImage but we could directly draw on the screen
        // It appears that this is faster to draw on a bufferImage and then
        // draw the image on the screen... (whatever the rendering engine)
        img = newImage(currentScreenDevice.getDefaultConfiguration(), width, height);

        // At each frame, we get a reference on the rendering buffer graphics2d.
        // To handle concurrency, we 'cut' it into graphics context for each cube.
        gi = img.createGraphics();
        gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        gi.setBackground(Color.BLACK);

        // each cube has its own graphics context
        // It could be reduce to each thread
        tiles = new Graphics2D[cubesCount];

        for (int k = 0; k < yCount; k++) {
            for (int i = 0; i < xCount; i++) {
                tiles[i + k * xCount] = (Graphics2D) gi.create(i * CUBE_SIZE, k * CUBE_SIZE, CUBE_SIZE, CUBE_SIZE);
            }
        }
    }

    static BufferedImage newImage(final GraphicsConfiguration gc, final int w, final int h) {
        if (USE_GRAPHICS_ACCELERATION) {
            return gc.createCompatibleImage(w, h);
        }
        return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * Called before rendering each frame...
     * @param g2 the graphics context of the fullscreen frame
     */
    @Override
    void updateAndRender(final Graphics2D g2, final StringBuilder sb) {

        // Update the global angle of rotation
        ang += ANG_INC;
        if (ang >= ANG_SIZE) {
            ang -= ANG_SIZE;
        }

        // Update only 1 cube:
        final double ca = cos[ang];
        final double sa = sin[ang];

        // Rotation and projection
        for (int i = 0; i < 8; i++) {
            // rotation %x
            double x1 = cube.x[i];
            double y1 = cube.y[i] * ca + cube.z[i] * sa;
            double z1 = -cube.y[i] * sa + cube.z[i] * ca;
            // rotation %y
            double x2 = x1 * ca + z1 * sa;
            double y2 = y1;
            double z2 = -x1 * sa + z1 * ca;
            // rotation %z
            double x3 = x2 * ca + y2 * sa;
            double y3 = -x2 * sa + y2 * ca;
            double z3 = z2 + 32;
            // projection
            double dz = Math.sqrt((x3 * x3) + (y3 * y3) + (z3 * z3)); // never 0 in our case
            cube.xe[i] = cube.cx + (x3 * (CUBE_SIZE / 2)) / dz;
            cube.ye[i] = cube.cy + (y3 * (CUBE_SIZE / 2)) / dz;
        }

        // Rendering of visible sides (3 max by cube)
        // We compute the 'determinant' to choose side to draw
        for (int i = 0; i < 6; i++) {
            int idx = i * 4;
            final double xe0 = cube.xe[Cube.f[idx]];
            final double ye0 = cube.ye[Cube.f[idx]];
            idx += 1;
            final double xe1 = cube.xe[Cube.f[idx]];
            final double ye1 = cube.ye[Cube.f[idx]];
            idx += 1;
            final double xe2 = cube.xe[Cube.f[idx]];
            final double ye2 = cube.ye[Cube.f[idx]];
            idx += 1;
            final double xe3 = cube.xe[Cube.f[idx]];
            final double ye3 = cube.ye[Cube.f[idx]];

            final double ax = xe1 - xe0;
            final double ay = ye1 - ye0;
            final double bx = xe3 - xe0;
            final double by = ye3 - ye0;

            if ((ax * by - bx * ay) < 0.0) {
                cube.visible[i] = true;

                final Path2D.Float p = cube.sides[i];
                p.reset();
                p.moveTo(xe0, ye0);
                p.lineTo(xe1, ye1);
                p.lineTo(xe2, ye2);
                p.lineTo(xe3, ye3);	// Comment to get triangle instead quad

            } else {
                cube.visible[i] = false;
            }
        }

        // Render
        long time = System.nanoTime();

        // Clear screen
        gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
//            gi.setBackground((frameCount % 2 == 0) ? Color.BLACK : Color.WHITE);
        gi.clearRect(0, 0, width, height);
        gi.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Rotate and draw in 1 or n threads (quick spread between threads...)
        if (THREADS_COUNT > 1) {
            futures.clear();

            for (int i = 0; i < THREADS_COUNT; i++) {
                futures.add(pool.submit(drawers[i]));
            }

            try {
                for (int i = 0; i < THREADS_COUNT; i++) {
                    futures.get(i).get();
                }
            } catch (Exception ignored) {
            }

        } else {
            for (int i = 0, len = cubesCount; i < len; i++) {
                drawCube(i);
            }
        }

        time = System.nanoTime() - time;

        // Real fps count to render our cubes. The display rate is physically 
        // maximized by 60 fps courtesy of FullScreen class
        final long fps = Math.round(1e9d / time);
        if (frameCount > MIN_FRAMES) {
            if (minFPS > fps) {
                minFPS = fps;
            }
            if (maxFPS < fps) {
                maxFPS = fps;
            }
            averageFPS += fps;
        }
        frameCount++;

        sb.setLength(0);
        sb.append("Rdr FPS: ").append(fps);

        // Paint on given buffer:
        g2.drawImage(img, 0, 0, null);

        final String infoFPS = sb.toString();
        g2.setColor((fps < 60) ? Color.RED : Color.YELLOW);
        g2.drawString(infoFPS, 100, 100);
    }

    /**
     * Rotation and projection "a l'ancienne" :-)
     * Sides are drawn by Java2d fill(Shape).
     * @param j index of the cube in tiles[]
     */
    void drawCube(final int j) {
        if (SKIP_RENDER) {
            return;
        }

        final Cube c = cube;
        final Graphics2D g = tiles[j];

        // Rendering of visible sides (3 max by cube)
        for (int i = 0; i < 6; i++) {
            if (c.visible[i]) {
                g.setColor(Cube.fcol[i]);
                g.fill(c.sides[i]);
            }
        }
    }

    @Override
    void curtain() {
        for (Graphics2D g0 : tiles) {
            g0.dispose();
        }
        gi.dispose();

        try {
            System.out.printf("Results with %d threads and cube size = %d (%d sides) ------------------\n",
                    Cubes.THREADS_COUNT, Cubes.CUBE_SIZE, cubesCount * 3
            );
            if (frameCount > MIN_FRAMES) {
                System.out.println(String.format(" Min FPS: %d", minFPS));
                System.out.println(String.format(" Max FPS: %d", maxFPS));
                System.out.println(String.format(" Avg FPS: %d", (averageFPS / (frameCount - MIN_FRAMES))));
            } else {
                System.out.println("Not enough data !");
            }
        } catch (Exception ignored) {

        }
    }

}
