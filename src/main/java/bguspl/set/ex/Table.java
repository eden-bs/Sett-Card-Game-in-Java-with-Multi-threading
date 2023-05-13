package bguspl.set.ex;

import bguspl.set.Env;

import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    /**
     * List of current empty slots on the table
     */
    protected ArrayList<Integer> emptySlots = new ArrayList<>(); //


    /**
     * Mapping between a slot and the tokens in it (null if none).
     */
    protected ArrayList<Integer>[] slotToTokens; // tokens per slot (if any)

    protected volatile boolean isTableReady = false;
    protected LinkedBlockingQueue<Integer> submittedSets;


    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {

        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        slotToTokens = new ArrayList[env.config.rows * env.config.columns];
        for (int i = 0; i < slotToTokens.length; i++) {
            slotToTokens[i] = new ArrayList<>(0);
            emptySlots.add(i);
        }
        submittedSets = new LinkedBlockingQueue<>();


    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted().collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = 0;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, (Integer) slot);
        emptySlots.remove((Integer) slot);
    }

    public void fillTable(ArrayList<Integer> drawnCards){
        if (emptySlots.isEmpty())
            return;
        while (!emptySlots.isEmpty()){
            if (!drawnCards.isEmpty()) {
                int emptySlot = emptySlots.remove(0);
                int drawnCard = drawnCards.remove(0);
                placeCard(drawnCard, emptySlot);
            }
            else break;
        }
        if (env.config.hints)
            hints();
    }

    /**
     * Removes a card from a grid slot on the table.
     * @param slot - the slot from which to remove the card.
     */
    public void removeCard(int slot) {
        removeTokensFromSlot(slot);
        env.ui.removeCard(slot);
        int cardToRemove = slotToCard[slot];
        cardToSlot[cardToRemove] = null;
        slotToCard[slot] = null;
        emptySlots.add((Integer) slot);
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {}
    }

    public void removeAllCardsFromTable() {
        for (int i = 0; i < slotToCard.length; i++)
            if (slotToCard[i] != null)
                removeCard(i);
    }

    /**
     * Places a player token on a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public void placeToken(int player, int slot) {
        if (slotToCard[slot] != null) {
            if (!slotToTokens[slot].contains(player)) {
                slotToTokens[slot].add(player);
                this.env.ui.placeToken(player, slot);
            }
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return       - true iff a token was successfully removed.
     */
    public boolean removeToken(int player, int slot) {
        if (slotToCard[slot]!=null) {
            for (int i = 0; i < slotToTokens[slot].size(); i++) {
                if (slotToTokens[slot].get(i) == player) {
                    slotToTokens[slot].remove(i);
                    this.env.ui.removeToken(player, slot);
                    return true;
                }
            }
        }
        return false;
    }

//    public boolean handleKeyPress(int player, int slot){
//        boolean didPlace = false;
//        if (!removeToken(player,slot)) {
//            placeToken(player, slot);
//            didPlace = true;
//        }
//        return didPlace;
//    }

    /**
     * Removes all tokens from a grid slot.
     */
    public void removeTokensFromSlot(int slot) {
        for (int p = 0; p<env.config.players;p++ )
            removeToken(p,slot);
    }

    public List<Integer> getCurrentCardsOnTable(){
        List<Integer> currentCardsOnTable = new LinkedList<>();
        for (int i = 0; i<slotToCard.length;i++) {
            if (slotToCard[i] != null)
                currentCardsOnTable.add(slotToCard[i]);
        }
        return currentCardsOnTable;
    }

//    public List<Integer> getCurrentEmptySlotsOnTable(){
//        List<Integer> emptySlots = new LinkedList<>();
//        for (Integer slot: slotToCard){
//            if (slot == null)
//                emptySlots.add()
//
//        }
//    }

}