package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    protected final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    protected final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    protected Object dealerLock = new Object();

    protected Thread dealerThread;

    protected boolean gameModeRegular;


    /**
     * The array of all created player threads
     */
    private Thread[] playerThreads;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        gameModeRegular = env.config.turnTimeoutMillis > 0;

    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.info("Thread " + Thread.currentThread().getName() + " starting.");
        playerThreads = new Thread[players.length];
        for (int i = 0; i < players.length; i++) {
            Thread playerThread = new Thread(players[i], "player" + players[i].id);
            playerThreads[i] = playerThread;
            playerThread.start();
        }
        dealerThread = Thread.currentThread();
//        updateTimerDisplay(true);

        while (!shouldFinish()) {
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
            timerLoop();
            removeAllCardsFromTable();
        }
        announceWinners();
        terminate();
        env.logger.info("Thread " + Thread.currentThread().getName() + " terminated.");
    }


    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        if (gameModeRegular) {
            while (!terminate && System.currentTimeMillis() < reshuffleTime) {
                sleepUntilWokenOrTimeout();
                updateTimerDisplay(false);
                removeCardsFromTable();
                placeCardsOnTable();
            }
        } else {
            while (!terminate) {
                sleepUntilWokenOrTimeout();
                updateTimerDisplay(false);
                removeCardsFromTable();
//                altGameModeFillTable();
                if (env.util.findSets(table.getCurrentCardsOnTable(), 1).size() == 0)
                    removeAllCardsFromTable();
                placeCardsOnTable();
            }
        }
    }

//    private void altGameModeFillTable(){
//        if (!table.emptySlots.isEmpty()) {
//            List<int[]> getLegalSetsFromDeck = env.util.findSets(deck,1);
//            ArrayList<Integer> drawnCards = new ArrayList<>();
//            if (!getLegalSetsFromDeck.isEmpty());
//            for (Integer card: getLegalSetsFromDeck.get(0)){
//                deck.remove((Integer)card);
//                drawnCards.add(card);
//            }
//            table.fillTable(drawnCards);
//        }
//    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        for (int i = playerThreads.length - 1; i >= 0; i--) {
            players[i].wakePlayer();
            players[i].terminate();
            while (players[i].getPlayerThread().isAlive())
            try {
                playerThreads[i].join();
            } catch (InterruptedException ignored) {
            }
            terminate = true;
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    protected boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    protected void submitSetToQueue(Integer playerId) {
        table.submittedSets.add(playerId);
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (!table.submittedSets.isEmpty()) {
            int playerToCheckId = table.submittedSets.poll();
            Player playerToCheck = players[playerToCheckId];
            ArrayList<Integer> tokenSlotList = playerToCheck.tokensPlaced;
            if (!playerToCheck.isSetFull()){
                playerToCheck.keyPressQueue.clear();
                playerToCheck.wakePlayer();
                playerToCheck.currentStatus = 0;
                return;
            }
            int[] setToCheck = playerToCheck.getSet();
            if (playerToCheck.isSetFull()) {
                if (env.util.testSet(setToCheck)) {
                    System.out.println(deck.size() + table.getCurrentCardsOnTable().size() + " player" + playerToCheckId + " has legal set " + "score: " + players[playerToCheckId].score);
                    table.isTableReady = false;
                    playerToCheck.currentStatus = 1;
                    while (!tokenSlotList.isEmpty()) {
                        Integer playerTokenSlot = tokenSlotList.remove(0);
                        for (Player player : players) {
                            player.tokensPlaced.remove(playerTokenSlot);
//                            player.keyPressQueue.remove(playerTokenSlot);
                        }
                        table.removeCard(playerTokenSlot);
                    }
                    updateTimerDisplay(true);
                    table.isTableReady = true;
                } else playerToCheck.currentStatus = 2;
            }
            playerToCheck.wakePlayer();
        }
    }


    public void wakeDealer() {
        synchronized (dealerLock) {
            dealerLock.notifyAll();
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        Collections.shuffle(deck);
        Collections.shuffle(table.emptySlots);
        ArrayList<Integer> drawnCards = new ArrayList<>();
        if (deck.size() >= table.emptySlots.size()) {
            for (int slot : table.emptySlots)
                drawnCards.add(deck.remove(0));
        } else {
            while (!deck.isEmpty()) drawnCards.add(deck.remove(0));
        }
        table.fillTable(drawnCards);
        table.isTableReady = true;
//        if (env.util.findSets(deck, 1).size() == 0 && env.util.findSets(table.getCurrentCardsOnTable(), 1).size() == 0)
//            terminate();
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (dealerLock) {
            try {
                dealerLock.wait(50);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if (env.config.turnTimeoutMillis > 0) {
            if (reset) {
                reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis + 999;
            }
            boolean warn = reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis;
            long TimeToDisplay = Math.max(0, reshuffleTime - System.currentTimeMillis());
            env.ui.setCountdown(TimeToDisplay, warn);

        } else if (env.config.turnTimeoutMillis == 0) {
            if (reset) {
                reshuffleTime = System.currentTimeMillis();
            }
            env.ui.setCountdown(System.currentTimeMillis() - reshuffleTime, false);
        } else {
            return;
        }
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    protected void removeAllCardsFromTable() {
        table.isTableReady = false;
        for (int i = 0; i < table.slotToCard.length; i++)
            if (table.slotToCard[i] != null) deck.add(table.slotToCard[i]);
        for (Player player : players) {
            player.blockInput = false;
            player.currentStatus = 0;
            player.keyPressQueue.clear();
            player.tokensPlaced.clear();
        }
        table.removeAllCardsFromTable();
    }


    /**
     * finds the highest score out of all players who participated
     *
     * @post - returns the value of the highest score gained
     */
    protected int findMaxScore() {
        int maxScore = -1;
        for (Player player : players) {
//            System.out.println(player.score);
            maxScore = Math.max(maxScore, player.score());
        }
        return maxScore;
    }

    /**
     * finds the players who scored the max score out of all players who participated
     *
     * @post - returns an array of the ids of the winners
     */
    protected ArrayList<Integer> getWinnerList() {
        ArrayList<Integer> winnerList = new ArrayList<Integer>();
        int maxScore = findMaxScore();
        for (Player player : players)
            if (player.score() == maxScore) winnerList.add(player.id);
        return winnerList;
    }


    /**
     * Check who is/are the winner/s and displays them.
     */
    protected void announceWinners() {
        ArrayList<Integer> winnerList = getWinnerList();
        int scores = 0;
        for (Player player : players) {
            scores += player.score;
        }
        int[] winners = new int[winnerList.size()];
        for (int i = 0; i < winners.length; i++)
            winners[i] = winnerList.get(i);
        System.out.println("scores: " + scores + " deck: " + deck.size() + " overall cards used: " + (scores * 3 + deck.size() + table.getCurrentCardsOnTable().size()));
        env.ui.announceWinner(winners);
    }
    

    public Thread getThread() {
        return dealerThread;
    }
}