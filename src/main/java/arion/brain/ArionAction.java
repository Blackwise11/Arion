/*
 * Arion — an AI companion mod that REQUIRES Baritone.
 *
 * Arion is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package arion.brain;

/**
 * A single structured decision from the brain: what to do, with what argument,
 * and what to say back to the player.
 *
 * @param verb one of: mine, follow, goto, hunt, explore, farm, get, pickup,
 *             build, clear, stop, chat
 * @param arg  the verb's argument (block name, "x y z", mob name, schematic
 *             name, region corners, or "")
 * @param say  a short message to relay to the player
 */
public record ArionAction(String verb, String arg, String say) {

    public static ArionAction chat(String say) {
        return new ArionAction("chat", "", say);
    }

    public boolean is(String v) {
        return verb.equalsIgnoreCase(v);
    }
}
