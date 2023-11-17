package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.UserInterface;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;
    private long sleepTime;
    private final long defaultSleepTime = 200;
    private final long turnTimeoutWarningSleepTime = 10;
    public final int setSize = 3;
    public Thread dealerThread;
    private final long DefaultGameRunTime;
    private long elapsedtime;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    public Queue<Integer> playersRequests;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminateOfExternal;
    private volatile boolean terminateTimeRunOut;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;
    private long RoundTime;
    private long timer = 0;
    private final int slotsInTable;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());

        this.playersRequests = new ConcurrentLinkedQueue<>();
        this.sleepTime = defaultSleepTime;
        this.slotsInTable = env.config.tableSize;
        this.DefaultGameRunTime = env.config.turnTimeoutMillis;
        this.elapsedtime = System.currentTimeMillis();
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        this.dealerThread = Thread.currentThread();
        createPlayerThreads();
        while (!shouldFinish()) {
            placeCardsOnTable();
            RoundTime = System.currentTimeMillis() + DefaultGameRunTime;
            timerLoop();
            terminateTimeRunOut = false;
            updateTimerDisplay(false);
            removeAllCardsFromTable();
            shuffle();

        }
        for (Player p : players) {
            p.terminate();
        }
        announceWinners();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminateOfExternal && !terminateTimeRunOut && System.currentTimeMillis() < reshuffleTime
                && (DefaultGameRunTime > 0 || table.SetOnTable())) {

            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();

        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        terminateOfExternal = true;
    }

    public boolean getTerminateOfExternal() {
        return this.terminateOfExternal;
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminateOfExternal || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */

    private void removeCardsFromTable() {
        while (!playersRequests.isEmpty()) {
            int playerID = playersRequests.poll();
            players[playerID].DealerisChecking = true;
            table.lock.writeLock().lock();

            if (table.playerToSlot[playerID].size() == setSize) {
                if (checkIfLegalSet(playerID)) {
                    for (int slott : table.playerToSlot[playerID]) {
                        table.removeCard(slott);
                    }
                    removeTokensFromLegalSet(playerID);

                    updateTimerDisplay(true);
                    players[playerID].isScore.add(true);

                } else {
                    players[playerID].isScore.add(false);

                }
            }
            table.lock.writeLock().unlock();
            players[playerID].DealerisChecking = false;
            if (!players[playerID].isFreezed) // check
                players[playerID].playerThread.interrupt();

        }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {

        shuffle();
        List<Integer> slotsToPlace = new ArrayList<>();
        for (int i = 0; i < slotsInTable; i++) {
            slotsToPlace.add(i);
        }
        Collections.shuffle(slotsToPlace);

        boolean needToPlace = false;
        for (Integer i : slotsToPlace) { // check if we need to place new cards on the table
            if (table.slotToCard[i] == null)
                needToPlace = true;
        }

        if (needToPlace) {
            table.lock.writeLock().lock();

            int FirstElement = 0;
            for (Integer i : slotsToPlace) {
                if (table.slotToCard[i] == null) {
                    if (deck.size() != 0) {
                        int newCard = deck.get(FirstElement);
                        deck.remove(FirstElement);
                        table.placeCard(newCard, i);
                    }
                }
            }
            table.lock.writeLock().unlock();

            if (env.config.hints)
                table.hints();

        }

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    public synchronized void sleepUntilWokenOrTimeout() {
        try {
            this.wait(sleepTime);
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (DefaultGameRunTime >= 0) {
            if (DefaultGameRunTime == 0) {
                if (reset) {
                    timer = 0;
                    env.ui.setElapsed(timer);
                    elapsedtime = System.currentTimeMillis();
                } else {
                    timer = System.currentTimeMillis() - elapsedtime;
                    env.ui.setElapsed(timer);

                }
            }
            if (DefaultGameRunTime > 0) {
                long newtimer = System.currentTimeMillis();
                timer = (RoundTime - newtimer);
                if (reset) {
                    RoundTime = System.currentTimeMillis() + DefaultGameRunTime;
                    timer = DefaultGameRunTime;
                    env.ui.setCountdown(timer, false);
                } else {

                    env.ui.setCountdown(Math.max(timer, 0), env.config.turnTimeoutWarningMillis > timer);
                    if (timer > env.config.turnTimeoutWarningMillis) {
                        sleepTime = defaultSleepTime;
                    } else {
                        sleepTime = turnTimeoutWarningSleepTime;
                    }

                    if (timer <= 50) {
                        terminateTimeRunOut = true;
                        sleepTime = defaultSleepTime;
                        RoundTime = System.currentTimeMillis() + DefaultGameRunTime;
                        timer = DefaultGameRunTime;
                    }
                }
            }
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        // do only on the critical part !!!

        table.lock.writeLock().lock();

        for (Player p : players)
            p.DealerisChecking = true;

        table.removeAllTokens();

        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] != null) {
                int currCard = table.slotToCard[i];
                deck.add(currCard);
                table.removeCard(table.cardToSlot[currCard]);

            }
        }
        for (Player p : players)
            p.DealerisChecking = false;
        table.lock.writeLock().unlock();

        for (Player p : players) {
            p.isFreezed = false;
        }

    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    public void announceWinners() {
        int highestScore = 0;

        for (Player player : players) {
            if (player.getScore() > highestScore)
                highestScore = player.getScore();
        }
        int winnersCounter = 0;
        for (Player player : players) {
            if (player.getScore() == highestScore)
                winnersCounter++;
        }

        int[] winners = new int[winnersCounter];
        winnersCounter = 0;
        for (Player player : players) {
            if (player.getScore() == highestScore) {
                winners[winnersCounter] = player.id;
                winnersCounter++;
            }

        }
        env.ui.announceWinner(winners);
    }

    private void createPlayerThreads() {
        for (Player player : players) {
            Thread t = new Thread(player);
            t.start();
        }
    }

    public void shuffle() {
        Collections.shuffle(deck);
    }

    public boolean checkIfLegalSet(int playerID) {

        int[] card = new int[setSize];
        int counter = 0;

        for (int i : table.playerToSlot[playerID]) {
            card[counter] = table.slotToCard[i];
            counter++;
        }

        return env.util.testSet(card);
    }

    public void removeTokensFromLegalSet(int playerID) {
        int counter = 0;
        while (counter < setSize) {
            int slot = table.playerToSlot[playerID].get(0);
            int size = table.slotToPlayer[slot].size();

            for (int k = 0; k < size; k++) {

                table.removeToken(table.slotToPlayer[slot].get(0), slot);
            }
            counter++;
        }
    }
}
