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
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.image.BufferStrategy;

/**
 * Base class to extend to run a full screen animation.
 * This class is a JFrame and enables a full screen rendering: a rendering thread executes
 * the traditional 'big loop'. At each frame a back buffer is set and rendered.
 *
 * To use it, extend this class and override the 
 * updateAndRender(Graphics2D g) method which will be executed at each frame (a 60 FPS 
 * rate is set by default).
 * Some protected attributes allows the inheriting class to access screen environment, frame, double buffer strategy
 * and rendering thread.
 * 
 * Aghnar / Temsoft in 2016
 *
 */
public abstract class FullScreen implements Runnable {

    public static final int NUM_BUFFERS = 2; // triple buffer, 2 = double buffer

    public static final long ROT_FRAMES = (360 / Cubes.ANG_INC); // 1 complete rotation

    public static final long MIN_FRAMES = ROT_FRAMES * 3; // calibration

    public static final long MAX_FRAMES = MIN_FRAMES + ROT_FRAMES * 30; // 30 complete rotations in benchmark

    /** Screen device */
    protected GraphicsDevice currentScreenDevice = null;

    /** 
     * Manages the double (or more) buffers process for rendering. The attribute is accessible
     * for potential content lost/restored event (see BufferStrategy)
     */
    protected BufferStrategy bufferStrategy = null;

    /** The main frame rendered in full screen */
    protected Frame mainFrame = null;

    /* The rendering thread */
    protected Thread renderThread = null;

    private long averageRealFPS = 0l;
    private long frameCount = 0l;

    private volatile boolean running = true;

    /*****************************************************************************
     * Main constructor that disables the usual paint event necessary for the loop
     * rendering process, sets the mandatory full screen mode and launches the rendering
     * thread
     */
    public FullScreen() {
        // Default screen parameters
        GraphicsEnvironment graphicsEnv = GraphicsEnvironment.getLocalGraphicsEnvironment();
        this.currentScreenDevice = graphicsEnv.getDefaultScreenDevice();

        // Full screen and start of the main loop
        if (this.currentScreenDevice.isFullScreenSupported()) {
            this.mainFrame = new Frame(this.currentScreenDevice.getDefaultConfiguration());

            // By Default, exit is done by 'esc' key
            this.mainFrame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                        stopThread();
                    }
                }
            });

            // For accurate rendering in full screen, the frame is empty and the Swing event 
            // rendering is deactivated
            this.mainFrame.setUndecorated(true);
            this.mainFrame.setResizable(false);
            this.mainFrame.setIgnoreRepaint(true);

            currentScreenDevice.setFullScreenWindow(this.mainFrame);
            renderThread = new Thread(this);
            renderThread.setPriority(Thread.MAX_PRIORITY - 1);
            renderThread.setName("CubeRenderThread");
        } else {
            System.out.println("The fullscreen mode is not supported. This template is designed to run in full screen.");
            System.exit(0);
        }

    }

    /***********************************************************************
     * The main loop, core of the rendering. Each iteration should last
     * a fixed amount of time (generally 1/60 s). 
     * Before the main loop, the graphics are initialized. In particular the
     * buffer strategy is created with two buffers. Probably the implementation
     * will set a page flipping strategy (exchange of video point back<->front
     * at each frame). 
     */
    @Override
    public void run() {
        mainFrame.createBufferStrategy(NUM_BUFFERS);
        bufferStrategy = mainFrame.getBufferStrategy();

        long time;
        long fps;
        String realFPS = null;
        final StringBuilder sb = new StringBuilder(16);

        while (running && frameCount < MAX_FRAMES) {
            time = System.nanoTime();

            // Render single frame
            do {
                do {
                    // Get a new graphics context every time through the loop
                    // to make sure the strategy is validated
                    final Graphics2D g = (Graphics2D) bufferStrategy.getDrawGraphics();

                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

                    // The main method to implement...
                    // Render to graphics
                    updateAndRender(g, sb);

                    // displaying real fps
                    if (realFPS != null) {
                        g.setColor(Color.YELLOW);
                        g.drawString(realFPS, 100, 70);
                    }

                    // Dispose the graphics
                    g.dispose();

                    // Repeat the rendering if the drawing buffer contents were restored
                } while (bufferStrategy.contentsRestored());

                // buffer swap() including (not very efficient) VSync()
                bufferStrategy.show();

                // Repeat the rendering if the drawing buffer was lost
            } while (bufferStrategy.contentsLost());

            // Real fps:
            time = System.nanoTime() - time;
            fps = Math.round(1e9d / time);

            sb.setLength(0);
            sb.append("Real FPS: ");
            sb.append(fps);
            realFPS = sb.toString();

            frameCount++;

            if (frameCount > MIN_FRAMES) {
                averageRealFPS += fps;
            }

        } // running

        exit();
    }

    void stopThread() {
        running = false;

        try {
            renderThread.join();
        } catch (InterruptedException ex) {
            System.out.println("Interrupted.");
        }
    }

    void exit() {
        curtain();

        if (frameCount > MIN_FRAMES) {
            System.out.println(String.format("Real Avg FPS: %d", (averageRealFPS / (frameCount - MIN_FRAMES))));
        }

        System.exit(0);
    }

    void start() {
        renderThread.start();
    }

    /**
     * This is the method redefine to draw on the fullscreen
     * @param g
     */
    abstract void updateAndRender(Graphics2D g, StringBuilder sb);

    abstract void curtain();

}
