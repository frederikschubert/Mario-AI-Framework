package engine.core;

import java.awt.image.BufferedImage;
import java.awt.image.VolatileImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.awt.*;
import java.awt.event.KeyAdapter;

import javax.swing.JFrame;

import agents.human.Agent;
import engine.helper.EventType;
import engine.helper.GameStatus;
import engine.helper.MarioActions;

public class MarioGame {
    /**
     * the maximum time that agent takes for each step
     */
    public static final long maxTime = 40;
    /**
     * extra time before reporting that the agent is taking more time that it should
     */
    public static final long graceTime = 10;
    /**
     * Screen width
     */
    public static final int width = 256;
    /**
     * Screen height
     */
    public static final int height = 256;
    /**
     * Screen width in tiles
     */
    public static final int tileWidth = width / 16;
    /**
     * Screen height in tiles
     */
    public static final int tileHeight = height / 16;
    /**
     * print debug details
     */
    public static final boolean verbose = false;

    /**
     * pauses the whole game at any moment
     */
    public boolean pause = false;

    /**
     * events that kills the player when it happens only care about type and param
     */
    private MarioEvent[] killEvents;

    // visualization
    private JFrame window = null;
    private MarioRender render = null;
    private MarioAgent agent = null;
    protected MarioWorld world = null;

    private VolatileImage renderTarget = null;
    private Graphics backBuffer = null;
    private Graphics currentBuffer = null;
    private ByteBuffer byteBuffer;
    private AsynchronousServerSocketChannel server;
    private AsynchronousSocketChannel worker;
    private float lastMarioTile = 0.0f;
    public Integer port;

    /**
     * Create a mario game to be played
     */
    public MarioGame() {
        try {
            this.port = this.getOpenPort();
            this.server = AsynchronousServerSocketChannel.open();
            this.server.bind(new InetSocketAddress("localhost", this.port));
            this.server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Object>() {

                @Override
                public void completed(AsynchronousSocketChannel result, Object attachment) {
                    worker = result;
                }

                @Override
                public void failed(Throwable exc, Object attachment) {
                    // process error
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private Integer getOpenPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0);) {
            return socket.getLocalPort();

        }
    }

    public int getPort() {
        return this.port;
    }

    public int getFrameSize() {
        return this.byteBuffer.capacity();
    }

    /**
     * Create a mario game with a different forward model where the player on
     * certain event
     *
     * @param killPlayer events that will kill the player
     */
    public MarioGame(MarioEvent[] killEvents) {
        this.killEvents = killEvents;
    }

    private int getDelay(int fps) {
        if (fps <= 0) {
            return 0;
        }
        return 1000 / fps;
    }

    private void setAgent(MarioAgent agent) {
        this.agent = agent;
        if (agent instanceof KeyAdapter) {
            this.render.addKeyListener((KeyAdapter) this.agent);
        }
    }

    /**
     * Play a certain mario level
     *
     * @param level a string that constitutes the mario level, it uses the same
     *              representation as the VGLC but with more details. for more
     *              details about each symbol check the json file in the levels
     *              folder.
     * @param timer number of ticks for that level to be played. Setting timer to
     *              anything <=0 will make the time infinite
     * @return statistics about the current game
     */
    public MarioResult playGame(String level, int timer) {
        return this.runGame(new Agent(), level, timer, 0, true, 30, 2);
    }

    /**
     * Play a certain mario level
     *
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @return statistics about the current game
     */
    public MarioResult playGame(String level, int timer, int marioState) {
        return this.runGame(new Agent(), level, timer, marioState, true, 30, 2);
    }

    /**
     * Play a certain mario level
     *
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @param fps        the number of frames per second that the update function is
     *                   following
     * @return statistics about the current game
     */
    public MarioResult playGame(String level, int timer, int marioState, int fps) {
        return this.runGame(new Agent(), level, timer, marioState, true, fps, 2);
    }

    /**
     * Play a certain mario level
     *
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @param fps        the number of frames per second that the update function is
     *                   following
     * @param scale      the screen scale, that scale value is multiplied by the
     *                   actual width and height
     * @return statistics about the current game
     */
    public MarioResult playGame(String level, int timer, int marioState, int fps, float scale) {
        return this.runGame(new Agent(), level, timer, marioState, true, fps, scale);
    }

    /**
     * Run a certain mario level with a certain agent
     *
     * @param agent the current AI agent used to play the game
     * @param level a string that constitutes the mario level, it uses the same
     *              representation as the VGLC but with more details. for more
     *              details about each symbol check the json file in the levels
     *              folder.
     * @param timer number of ticks for that level to be played. Setting timer to
     *              anything <=0 will make the time infinite
     * @return statistics about the current game
     */
    public MarioResult runGame(MarioAgent agent, String level, int timer) {
        return this.runGame(agent, level, timer, 0, false, 0, 2);
    }

    /**
     * Run a certain mario level with a certain agent
     *
     * @param agent      the current AI agent used to play the game
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @return statistics about the current game
     */
    public MarioResult runGame(MarioAgent agent, String level, int timer, int marioState) {
        return this.runGame(agent, level, timer, marioState, false, 0, 2);
    }

    /**
     * Run a certain mario level with a certain agent
     *
     * @param agent      the current AI agent used to play the game
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @param visuals    show the game visuals if it is true and false otherwise
     * @return statistics about the current game
     */
    public MarioResult runGame(MarioAgent agent, String level, int timer, int marioState, boolean visuals) {
        return this.runGame(agent, level, timer, marioState, visuals, visuals ? 30 : 0, 2);
    }

    /**
     * Run a certain mario level with a certain agent
     *
     * @param agent      the current AI agent used to play the game
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @param visuals    show the game visuals if it is true and false otherwise
     * @param fps        the number of frames per second that the update function is
     *                   following
     * @return statistics about the current game
     */
    public MarioResult runGame(MarioAgent agent, String level, int timer, int marioState, boolean visuals, int fps) {
        return this.runGame(agent, level, timer, marioState, visuals, fps, 2);
    }

    /**
     * Run a certain mario level with a certain agent
     *
     * @param agent      the current AI agent used to play the game
     * @param level      a string that constitutes the mario level, it uses the same
     *                   representation as the VGLC but with more details. for more
     *                   details about each symbol check the json file in the levels
     *                   folder.
     * @param timer      number of ticks for that level to be played. Setting timer
     *                   to anything <=0 will make the time infinite
     * @param marioState the initial state that mario appears in. 0 small mario, 1
     *                   large mario, and 2 fire mario.
     * @param visuals    show the game visuals if it is true and false otherwise
     * @param fps        the number of frames per second that the update function is
     *                   following
     * @param scale      the screen scale, that scale value is multiplied by the
     *                   actual width and height
     * @return statistics about the current game
     */
    public MarioResult runGame(MarioAgent agent, String level, int timer, int marioState, boolean visuals, int fps,
            float scale) {
        if (visuals) {
            this.window = new JFrame("Mario AI Framework");
            this.render = new MarioRender(scale);
            this.window.setContentPane(this.render);
            this.window.pack();
            this.window.setResizable(false);
            this.window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            this.render.init();
            this.window.setVisible(true);
        }
        this.setAgent(agent);
        return this.gameLoop(level, timer, marioState, visuals, fps);
    }

    private MarioResult gameLoop(String level, int timer, int marioState, boolean visual, int fps) {
        this.world = new MarioWorld(this.killEvents);
        this.world.visuals = visual;
        this.world.initializeLevel(level, 1000 * timer);
        if (visual) {
            this.world.initializeVisuals(this.render.getGraphicsConfiguration());
        }
        this.world.mario.isLarge = marioState > 0;
        this.world.mario.isFire = marioState > 1;
        this.world.update(new boolean[MarioActions.numberOfActions()]);
        long currentTime = System.currentTimeMillis();

        // initialize graphics
        VolatileImage renderTarget = null;
        Graphics backBuffer = null;
        Graphics currentBuffer = null;
        if (visual) {
            renderTarget = this.render.createVolatileImage(MarioGame.width, MarioGame.height);
            backBuffer = this.render.getGraphics();
            currentBuffer = renderTarget.getGraphics();
            this.render.addFocusListener(this.render);
        }

        MarioTimer agentTimer = new MarioTimer(MarioGame.maxTime);
        this.agent.initialize(new MarioForwardModel(this.world.clone()), agentTimer);

        ArrayList<MarioEvent> gameEvents = new ArrayList<>();
        ArrayList<MarioAgentEvent> agentEvents = new ArrayList<>();
        while (this.world.gameStatus == GameStatus.RUNNING) {
            if (!this.pause) {
                // get actions
                agentTimer = new MarioTimer(MarioGame.maxTime);
                boolean[] actions = this.agent.getActions(new MarioForwardModel(this.world.clone()), agentTimer);
                if (MarioGame.verbose) {
                    if (agentTimer.getRemainingTime() < 0
                            && Math.abs(agentTimer.getRemainingTime()) > MarioGame.graceTime) {
                        System.out.println("The Agent is slowing down the game by: "
                                + Math.abs(agentTimer.getRemainingTime()) + " msec.");
                    }
                }
                // update world
                this.world.update(actions);
                gameEvents.addAll(this.world.lastFrameEvents);
                agentEvents.add(new MarioAgentEvent(actions, this.world.mario.x, this.world.mario.y,
                        (this.world.mario.isLarge ? 1 : 0) + (this.world.mario.isFire ? 1 : 0),
                        this.world.mario.onGround, this.world.currentTick));
            }

            // render world
            if (visual) {
                this.render.renderWorld(this.world, renderTarget, backBuffer, currentBuffer);
            }
            // check if delay needed
            if (this.getDelay(fps) > 0) {
                try {
                    currentTime += this.getDelay(fps);
                    Thread.sleep(Math.max(0, currentTime - System.currentTimeMillis()));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return new MarioResult(this.world, gameEvents, agentEvents);
    }

    public void initGame() throws InterruptedException, ExecutionException, TimeoutException {
        this.window = new JFrame("Mario AI Framework");
        this.render = new MarioRender(1);
        this.window.setContentPane(this.render);
        this.window.pack();
        this.window.setResizable(false);
        this.window.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        this.render.init();
        this.window.setVisible(true);
        this.byteBuffer = ByteBuffer.allocateDirect(Integer.SIZE / Byte.SIZE * MarioGame.height * MarioGame.width * 3);
        this.byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
    }

    public void resetGame(String level, int timer) throws InterruptedException, ExecutionException {
        this.world = new MarioWorld(this.killEvents);
        this.world.visuals = true;
        this.world.initializeLevel(level, 1000 * timer);
        this.world.initializeVisuals(this.render.getGraphicsConfiguration());
        this.world.update(new boolean[MarioActions.numberOfActions()]);
        this.lastMarioTile = 0.0f;

        // initialize graphics
        this.renderTarget = this.render.createVolatileImage(MarioGame.width, MarioGame.height);
        this.backBuffer = this.render.getGraphics();
        this.currentBuffer = renderTarget.getGraphics();
        this.render.addFocusListener(this.render);
    }

    public boolean computeDone() {
        return this.world.gameStatus != GameStatus.RUNNING;
    }

    public void stepGame(boolean left, boolean right, boolean down, boolean speed, boolean jump)
            throws InterruptedException, ExecutionException {
        boolean[] actions = new boolean[5];
        actions[0] = left;
        actions[1] = right;
        actions[2] = down;
        actions[3] = speed;
        actions[4] = jump;
        this.world.update(actions);

    }

    public float getCompletionPercentage() {
        return this.world.mario.x / (this.world.level.exitTileX * 16);
    }

    public float getHeightBonus() {
        return this.world.mario.y / (this.world.level.height);
    }

    public float computeReward() {
        float reward = 0;
        int marioTile = this.world.mario.getMapX();
        for (MarioEvent event : this.world.lastFrameEvents) {
            EventType type = EventType.values()[event.getEventType() - 1];
            switch (type) {
                case COLLECT:
                    reward += 1.0f;
                    break;
                case STOMP_KILL:
                case FIRE_KILL:
                case SHELL_KILL:
                case FALL_KILL:
                    if (marioTile > lastMarioTile) {
                        reward += 1.0f;
                    }
                    break;
                case BUMP:
                    break;
                case JUMP:
                    break;
                case LAND:
                    break;
                case HURT:
                    reward -= 1.0f;
                    break;
                case KICK:
                    break;
                case LOSE:
                    break;
                case WIN:
                    reward += 10.0f;
                    break;
            }
        }
        if (marioTile > lastMarioTile + 8) {
            lastMarioTile = marioTile;
            reward += 1.0f;
        }
        return reward;
    }

    public void computeObservationRGB() throws InterruptedException, ExecutionException {
        this.render.renderWorld(this.world, this.renderTarget, this.backBuffer, this.currentBuffer);
        BufferedImage image = this.renderTarget.getSnapshot();
        byteBuffer.clear();
        for (int y = 0; y < MarioGame.height; y++) {
            for (int x = 0; x < MarioGame.width; x++) {
                int pixel = image.getRGB(x, y);
                Color color = new Color(pixel);
                byteBuffer.putInt(color.getRed()).putInt(color.getGreen()).putInt(color.getBlue());
            }
        }
        byteBuffer.flip();
        this.worker.write(byteBuffer).get();
    }
}
