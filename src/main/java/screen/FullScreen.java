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

    public static final int NUM_BUFFERS = 2;

    public static final long MAX_FRAMES = 60 * 20; // ~20s at 60 FPS

    /* The fixed wanted duration of a frame in nanosecond. Example : 1/60=16666666... */
    private static long PERIOD = 16666666;

    /*
	 * Screen device 
     */
    protected GraphicsDevice currentScreenDevice = null;

    /* 
	 * Manages the double (or more) buffers process for rendering. The attribute is accessible
	 * for potential content lost/restored event (see BufferStrategy)
     */
    protected BufferStrategy bufferStrategy = null;

    /* 
	 * The main frame rendered in full screen
     */
    protected Frame mainFrame = null;

    /*
	 * The rendering thread
     */
    protected Thread renderThread = null;

    private long averageRealFPS = 0l;
    private long numRealFPS = 0l;

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
            renderThread.setName("Rendering demo thread");
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

        mainFrame.createBufferStrategy(NUM_BUFFERS);			// 2 = double buffer
        bufferStrategy = mainFrame.getBufferStrategy();

        long time;
        int n;
        final StringBuilder sb = new StringBuilder(16);
        String realFPS = null;
        long fps;

        while (running && numRealFPS < MAX_FRAMES) {
            n = 0;
            time = System.nanoTime();

            // Render single frame
            do {
                do {
                    n++;

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

                    // Repeat the rendering if the drawing buffer contents
                    // were restored
                } while (bufferStrategy.contentsRestored());

                // buffer swap() including (not very efficient) VSync()
                bufferStrategy.show();

                // Repeat the rendering if the drawing buffer was lost
            } while (bufferStrategy.contentsLost());

            time = System.nanoTime() - time;
            // real fps
            fps = Math.round(1e9d * n / time);

            averageRealFPS += fps;
            numRealFPS++;

            sb.setLength(0);
            sb.append("Real FPS : ");

            sb.append(fps);
            realFPS = sb.toString();

            // sync()
            /*long realPeriod=System.nanoTime()-time;
			if(realPeriod<PERIOD)
			try {
				long ms=(PERIOD-realPeriod)/1000000;
				int nanos=(int)((PERIOD-realPeriod)-(ms*1000000));
				// NB if(ms>0 || nanos>300000) if nano too small and ms=0, the call of Thread.sleep itself can be too long... 
				Thread.sleep(ms,nanos);
			} catch (InterruptedException e) {
				System.out.println("Interruption...");
			}*/
        } // running

//        System.out.println("Rendering stopped.");
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

    public void exit() {
        curtain();

        System.out.println(String.format("Real FPS avg : %d", (averageRealFPS / numRealFPS)));

        System.exit(0);
    }

    protected void start() {
        renderThread.start();
    }

    /**
     * This is the method redefine to draw on the fullscreen
     * @param g
     */
    public abstract void updateAndRender(Graphics2D g, StringBuilder sb);

    public abstract void curtain();

}
