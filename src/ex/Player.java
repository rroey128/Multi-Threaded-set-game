package bguspl.set.ex;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import bguspl.set.Env;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {
    boolean DealerisChecking = false;
    public int[] tokens;
    public Queue<Integer> queue;
    public volatile boolean isFreezed = false;
    private final long Sleeptime = 500;
    public Queue<Boolean> isScore;
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    public Thread playerThread; // public for testing

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private Dealer dealer;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.queue = new ConcurrentLinkedQueue<>();
        this.dealer = dealer;
        this.isScore = new ConcurrentLinkedQueue<>();

    }

    public int getID() {
        return this.id;
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            synchronized (this) {
                try {
                    this.wait();
                } catch (InterruptedException e) {
                }

            }
            while (!queue.isEmpty()) {
                int slot = queue.poll();

                if (!DealerisChecking) {

                    table.wrapper(this.id, slot); // places or removes a token
                }

                synchronized (queue) {
                    if (maxTokensPlaced()) {
                        DealerisChecking = true;

                        dealer.playersRequests.add(this.id);
                        synchronized (dealer) {
                            dealer.notifyAll();
                        }
                        synchronized (this) {
                            try {

                                this.wait();
                            } catch (InterruptedException e) {
                                this.queue.clear();

                                if (!isScore.isEmpty()) {
                                    if (isScore.poll())
                                        this.point();
                                    else
                                        this.penalty();
                                }
                            }
                        }
                    }
                }

            }
        }

        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {

                try {
                    synchronized (this) {
                        if (isFreezed)
                            this.wait();
                    }
                } catch (InterruptedException ignored) {
                }
                Random random = new Random();
                this.keyPressed(random.nextInt(env.config.tableSize)); // enter random slots in prefixed time intervals

            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        this.terminate = true;
        synchronized (this) {
            this.notifyAll();
        }
    }

    public boolean getTerminate() {
        return this.terminate;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (DealerisChecking || queue.size() >= dealer.setSize || isFreezed || table.slotToCard[slot] == null) {

            return;
        }
        table.lock.readLock().lock();

        try {
            if (!maxTokensPlaced() || (maxTokensPlaced() && table.slotToPlayer[slot].contains(this.id))) {
                queue.add(slot);

                playerThread.interrupt();

            }
        } finally {
            table.lock.readLock().unlock();

        }

    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
        freeze(env.config.pointFreezeMillis);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        freeze(env.config.penaltyFreezeMillis);
    }

    public void freeze(long millies) {
        if (millies > 0) {
            try {
                isFreezed = true;

                long startTime = System.currentTimeMillis();
                long untilTIme = System.currentTimeMillis() + millies;

                while (startTime < untilTIme && isFreezed && !terminate) {
                    startTime = System.currentTimeMillis();
                    env.ui.setFreeze(this.id, untilTIme - startTime + Sleeptime);
                    synchronized (this) {
                        this.wait(Sleeptime);
                    }
                }
                env.ui.setFreeze(this.id, 0);
                isFreezed = false;
                if (aiThread != null)
                    aiThread.interrupt();
            } catch (Exception e) {
            }
            ;

        }

    }

    public int getScore() {
        return score;
    }

    public boolean maxTokensPlaced() {
        table.lock.readLock().lock();
        boolean Maxed = table.playerToSlot[this.id].size() == dealer.setSize;
        table.lock.readLock().unlock();

        return Maxed;
    }
}
