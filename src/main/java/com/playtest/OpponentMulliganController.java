package com.playtest;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.CardRules;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Only the opponent seat overrides opening-hand choices. All other engine decisions are inherited. */
public final class OpponentMulliganController extends PlayerControllerAi {
    private final OpeningHandPolicy.Profile profile;
    private final Set<String> engines;
    private int redraws;
    private boolean play = true;

    public OpponentMulliganController(Game game, Player player, LobbyPlayer lobby, Deck deck, Set<String> engines, boolean combo) {
        super(game, player, lobby);
        this.engines = Set.copyOf(engines);
        List<OpeningHandPolicy.CardInfo> submitted = new ArrayList<>();
        Map<Integer, Integer> sources = new HashMap<>();
        int id = 0;
        for (var entry : deck.getMain()) {
            for (int n = 0; n < entry.getValue(); n++) {
                var info = describe(id++, entry.getKey().getRules(), engines);
                submitted.add(info);
                if (info.land()) sources.merge(info.sources(), 1, Integer::sum);
            }
        }
        profile = new OpeningHandPolicy.Profile(submitted.size(), sources, OpeningHandPolicy.infer(submitted, combo));
        setupAutoProfile(deck);
    }

    static OpeningHandPolicy.CardInfo describe(int id, CardRules rules, Set<String> engines) {
        var face = rules.getMainPart();
        String name = face.getName();
        String text = face.getOracleText().toLowerCase(java.util.Locale.ROOT);
        var type = face.getType();
        boolean land = type.isLand();
        int sources = 0;
        String[] basics = {"plains", "island", "swamp", "mountain", "forest"};
        String[] colours = {"w", "u", "b", "r", "g", "c"};
        // Only mana-production clauses, not coloured activation costs or unrelated symbols.
        if (land) {
            for (int i = 0; i < basics.length; i++) if (type.hasSubtype(capitalize(basics[i]))) sources |= 1 << i;
            var clauses = Pattern.compile("add ([^.]+)").matcher(text);
            while (clauses.find()) {
                String clause = clauses.group(1);
                if (clause.contains("any color") || clause.contains("any colour")) sources |= 31;
                for (int i = 0; i < colours.length; i++) if (clause.contains("{" + colours[i] + "}")) sources |= 1 << i;
            }
        }
        List<Integer> pips = new ArrayList<>();
        for (var shard : face.getManaCost()) {
            if (shard.isPhyrexian()) continue; // payable with life; this is a heuristic, not a payment override
            int mask = 0;
            for (int i = 0; i < colours.length; i++) if (shard.canBePaidWithManaOfColor((byte) (1 << i))) mask |= 1 << i;
            if (mask != 0) pips.add(mask);
        }
        boolean removal = text.contains("destroy target") || text.contains("exile target") || text.contains("damage to any target");
        boolean counter = text.contains("counter target spell");
        boolean discard = text.contains("discards") || text.contains("discard a card");
        boolean wipe = text.contains("destroy all") || text.contains("exile all");
        boolean ramp = text.contains("add {") || text.contains("add one mana") || (text.contains("search your library") && text.contains("land") && text.contains("battlefield"));
        boolean pregame = text.contains("opening hand") && text.contains("begin the game");
        boolean instantOrSorcery = type.isInstant() || type.isSorcery();
        return new OpeningHandPolicy.CardInfo(id, name, land, face.getManaCost().getCMC(), sources, pips,
            type.isCreature(), !land && ramp, instantOrSorcery && (removal || counter), removal, wipe,
            discard, counter, pregame, engines.contains(OpponentStrategy.key(name)), type.isLegendary());
    }
    private static String capitalize(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private List<OpeningHandPolicy.CardInfo> describeHand(CardCollectionView hand) {
        List<OpeningHandPolicy.CardInfo> result = new ArrayList<>();
        for (Card card : hand) result.add(describe(card.getId(), card.getPaperCard().getRules(), engines));
        return result;
    }
    @Override public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        play = firstPlayer == null || firstPlayer.equals(getPlayer());
        var hand = getPlayer().getCardsIn(ZoneType.Hand);
        var decision = OpeningHandPolicy.decide(describeHand(hand), profile, play, redraws,
            Math.max(0, cardsToReturn), OpeningHandPolicy.Archetype.UNKNOWN);
        // Private debug output stays on worker stderr; never publish bottomed names or hand analysis.
        System.err.println("AI mulligan " + redraws + " " + profile.archetype() + ": " + decision.score()
            + "/" + decision.threshold() + " " + (decision.keep() ? "keep" : "redraw") + " " + decision.reasons());
        if (decision.keep()) {
            // Forge supplies the authoritative, format-adjusted return count at this callback.
            for (Card card : chooseBottom(hand, Math.max(0, cardsToReturn))) getPlayer().getGame().getAction().moveToLibrary(card, -1, null);
        } else { redraws++; }
        return decision.keep();
    }
    @Override public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        if (cardsToReturn > getPlayer().getMaxHandSize()) return chooseBottom(hand, cardsToReturn);
        // London calls this after redraw, before Keep. Leave the seven intact for evaluation.
        return new CardCollection();
    }
    private CardCollectionView chooseBottom(CardCollectionView hand, int count) {
        var decision = OpeningHandPolicy.decide(describeHand(hand), profile, play, redraws, count, OpeningHandPolicy.Archetype.UNKNOWN);
        CardCollection result = new CardCollection();
        for (var bottom : decision.bottom()) for (Card card : hand) if (card.getId() == bottom.id()) result.add(card);
        return result;
    }
}