/*
 * Copyright (C) 2022 NotEnoughUpdates contributors
 *
 * This file is part of NotEnoughUpdates.
 *
 * NotEnoughUpdates is free software: you can redistribute it
 * and/or modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * NotEnoughUpdates is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with NotEnoughUpdates. If not, see <https://www.gnu.org/licenses/>.
 */

package io.github.moulberry.notenoughupdates.listener;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.moulberry.notenoughupdates.NotEnoughUpdates;
import io.github.moulberry.notenoughupdates.core.util.StringUtils;
import io.github.moulberry.notenoughupdates.events.RepositoryReloadEvent;
import io.github.moulberry.notenoughupdates.profileviewer.GuiProfileViewer;
import io.github.moulberry.notenoughupdates.util.Calculator;
import io.github.moulberry.notenoughupdates.util.Constants;
import io.github.moulberry.notenoughupdates.util.ItemResolutionQuery;
import io.github.moulberry.notenoughupdates.util.ItemUtils;
import io.github.moulberry.notenoughupdates.util.Utils;
import net.minecraft.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemTooltipRngListener {
	private final NotEnoughUpdates neu;
	private boolean showSlayerRngFractions = false;
	private boolean pressedShiftLast = false;
	private int currentSelected = 0;
	private boolean pressedArrowLast = false;
	private boolean repoReloadNeeded = true;

	private final Pattern ODDS_PATTERN = Pattern.compile("§5§o§7Odds: (.+) §7\\(§7(.*)%\\)");
	private final Pattern ODDS_SELECTED_PATTERN = Pattern.compile("§5§o§7Odds: (.+) §7\\(§8§m(.*)%§r §7(.+)%\\)");

	private final Pattern RUNS_PATTERN = Pattern.compile("§5§o§7(Dungeon Score|Slayer XP): §d(.*)§5/§d(.+)");
	private final Pattern RUNS_SELECTED_PATTERN = Pattern.compile("§5§o§d-(.+)- §d(.*)§5/§d(.+)");

	private final Pattern SLAYER_INVENTORY_TITLE_PATTERN = Pattern.compile("(.+) RNG Meter");

	private final Map<String, Integer> dungeonData = new LinkedHashMap<>();
	private final Map<String, LinkedHashMap<String, Integer>> slayerData = new LinkedHashMap<>();

	public ItemTooltipRngListener(NotEnoughUpdates neu) {
		this.neu = neu;
	}

	@SubscribeEvent
	public void onItemTooltip(ItemTooltipEvent event) {
		if (!neu.isOnSkyblock()) return;
		if (event.toolTip == null) return;
		if (!Utils.getOpenChestName().endsWith(" RNG Meter") && !slayerData.containsKey(Utils.getOpenChestName())) return;

		List<String> newToolTip = new ArrayList<>();

		boolean nextLineProgress = false;
		for (String line : event.toolTip) {

			if (line.contains("Odds:")) {
				if (NotEnoughUpdates.INSTANCE.config.tooltipTweaks.rngMeterFractionDisplay) {
					fractionDisplay(newToolTip, line);
					continue;
				}
			}

			if (nextLineProgress || line.contains("Dungeon Score:") || line.contains("Slayer XP:")) {
				Matcher matcher = RUNS_PATTERN.matcher(line);
				Matcher matcherSelected = RUNS_SELECTED_PATTERN.matcher(line);
				Matcher m = null;
				if (matcher.matches()) {
					m = matcher;
				} else if (matcherSelected.matches()) {
					m = matcherSelected;
				}

				if (m != null) {
					int having;
					try {
						having = Calculator.calculate(m.group(2).replace(",", "")).intValue();
					} catch (Calculator.CalculatorException e) {
						having = -1;
					}

					int needed;
					try {
						needed = Calculator.calculate(m.group(3).replace(",", "")).intValue();
					} catch (Calculator.CalculatorException e) {
						needed = -1;
					}
					if (NotEnoughUpdates.INSTANCE.config.tooltipTweaks.rngMeterRunsNeeded) {
						runsRequired(newToolTip, having, needed, nextLineProgress, event.itemStack);
					}

					if (NotEnoughUpdates.INSTANCE.config.tooltipTweaks.rngMeterProfitPerUnit) {
						if (!NotEnoughUpdates.INSTANCE.config.tooltipTweaks.rngMeterRunsNeeded) {
							String name = Utils.getOpenChestName().contains("Catacombs") ? "Score" : "XP";
							String formatCoinsPer = getFormatCoinsPer(event.itemStack, needed, 1, name);
							if (formatCoinsPer != null) {
								newToolTip.add(line);
								newToolTip.add(formatCoinsPer);
								continue;
							}
						}
					}
				}
				nextLineProgress = false;
			}

			if (line.contains("Progress:")) {
				nextLineProgress = true;
			}
			newToolTip.add(line);
		}

		event.toolTip.clear();
		event.toolTip.addAll(newToolTip);
	}

	private String getFormatCoinsPer(ItemStack stack, int needed, int multiplier, String label) {
		String internalName = neu.manager.createItemResolutionQuery().withItemStack(stack).resolveInternalName();
		double profit = neu.manager.auctionManager.getBazaarOrBin(internalName, false);
		if (profit <= 0) return null;

		//ask hypixel nicely to release a 'chest price api' with 4 dimensions for us. the 4 dimensions needed are: item name, floor, normal/mm, s/s+
//		double chestPrice = grabChestPrice(stack, internalName);
//		profit -= chestPrice;

		double coinsPer = (profit / needed) * multiplier;
		String format = StringUtils.shortNumberFormat(coinsPer);
		return "§7Coins per " + label + ": §6" + format + " coins";
	}

	private void fractionDisplay(List<String> newToolTip, String line) {
		boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
		if (!pressedShiftLast && shift) {
			showSlayerRngFractions = !showSlayerRngFractions;
		}
		pressedShiftLast = shift;

		String result;
		Matcher matcher = ODDS_PATTERN.matcher(line);
		Matcher matcherSelected = ODDS_SELECTED_PATTERN.matcher(line);
		if (matcher.matches()) {
			String odds = matcher.group(1);
			int baseChance = calculateChance(matcher.group(2));
			String baseFormat = GuiProfileViewer.numberFormat.format(baseChance);

			String fractionFormat = "§7(1/" + baseFormat + ")";
			result = odds + " " + fractionFormat;
		} else if (matcherSelected.matches()) {
			String odds = matcherSelected.group(1);
			int baseChance = calculateChance(matcherSelected.group(2));
			String baseFormat = GuiProfileViewer.numberFormat.format(baseChance);

			int increasedChance = calculateChance(matcherSelected.group(3));
			String increased = GuiProfileViewer.numberFormat.format(increasedChance);
			String fractionFormat = "§7(§8§m1/" + baseFormat + "§r §71/" + increased + ")";

			result = odds + " " + fractionFormat;
		} else {
			return;
		}

		if (showSlayerRngFractions) {
			newToolTip.add("§7Odds: " + result);
			newToolTip.add("§8[Press SHIFT to show odds as percentages]");
		} else {
			newToolTip.add(line);
			newToolTip.add("§8[Press SHIFT to show odds as fractions]");
		}
	}

	/**
	 * This adds support for the /neureloadrepo command
	 */
	@SubscribeEvent(priority = EventPriority.LOWEST)
	public void onRepoReload(RepositoryReloadEvent event) {
		repoReloadNeeded = true;
	}

	public void checkUpdateData() {
		if (repoReloadNeeded) {
			updateRepoData();
		}
	}

	private void updateRepoData() {
		slayerData.clear();
		dungeonData.clear();

		JsonObject leveling = Constants.LEVELING;
		if (leveling == null ||
			!leveling.has("slayer_boss_xp") ||
			!leveling.has("slayer_highest_tier") ||
			!leveling.has("slayer_tier_colors") ||
			!leveling.has("rng_meter_dungeon_score")) {
			Utils.showOutdatedRepoNotification();
			return;
		}

		List<Integer> slayerExp = new ArrayList<>();
		for (JsonElement element : leveling.get("slayer_boss_xp").getAsJsonArray()) {
			slayerExp.add(element.getAsInt());
		}

		List<String> slayerColors = new ArrayList<>();
		for (JsonElement element : leveling.get("slayer_tier_colors").getAsJsonArray()) {
			slayerColors.add(element.getAsString());
		}

		for (Map.Entry<String, JsonElement> entry : leveling.get("slayer_highest_tier").getAsJsonObject().entrySet()) {
			String slayerName = entry.getKey();
			int maxTier = entry.getValue().getAsInt();
			LinkedHashMap<String, Integer> singleSlayerData = new LinkedHashMap<>();
			for (int i = 0; i < maxTier; i++) {
				String name = slayerColors.get(i) + "Tier " + (i + 1);
				singleSlayerData.put(name, slayerExp.get(i));
			}
			slayerData.put(slayerName, singleSlayerData);
		}

		for (Map.Entry<String, JsonElement> entry : leveling.get("rng_meter_dungeon_score").getAsJsonObject().entrySet()) {
			String dungeonScore = entry.getKey();
			int score = entry.getValue().getAsInt();
			dungeonData.put(dungeonScore, score);
		}

		repoReloadNeeded = false;
	}

	private void runsRequired(
		List<String> toolTip,
		int having,
		int needed,
		boolean nextLineProgress,
		ItemStack stack
	) {
		checkUpdateData();
		if (repoReloadNeeded) return;

		String openChestName = Utils.getOpenChestName();
		Map<String, Integer> runsData;
		String labelPlural;
		String labelSingular;
		String repoCategory;
		if (openChestName.contains("Catacombs")) {
			runsData = dungeonData;
			labelPlural = "Runs";
			labelSingular = "Run";
			repoCategory = "catacombs";
		} else { // Slayer
			Matcher matcher = SLAYER_INVENTORY_TITLE_PATTERN.matcher(openChestName);
			if (!matcher.matches()) {
				//Happens for the first 4-5 ticks after inventory opens. Thanks hypixel
				return;
			}

			String slayerName = matcher.group(1);
			runsData = slayerData.get(slayerName);
			labelPlural = "Bosses";
			labelSingular = "Boss";
			repoCategory = "slayer";
		}

		int repoScore = getRepoScore(stack, repoCategory);
		if (repoScore != -1) {
			needed = repoScore;
		}

		handleArrowKeys(runsData);

		if (currentSelected >= runsData.keySet().size()) {
			currentSelected = 0;
		}

		String name = (String) runsData.keySet().toArray()[currentSelected];
		int gainPerRun = runsData.get(name);

		int runsNeeded = (int) Math.floor((double) needed / (double) gainPerRun);
		int runsHaving = having / gainPerRun;
		String runsNeededFormat = GuiProfileViewer.numberFormat.format(runsNeeded);
		String runsHavingFormat = GuiProfileViewer.numberFormat.format(runsHaving);

		String progressString = null;
		if (nextLineProgress) {
			progressString = toolTip.remove(toolTip.size() - 1);
		}

		toolTip.add("§9Stats for " + name + "§9: [§l§m< §9Switch§l➡§9]");
		toolTip.add(
			"   §7" + labelPlural + " completed: §e" + runsHavingFormat + " §7(of §e" + runsNeededFormat + " §7needed)");

		if (NotEnoughUpdates.INSTANCE.config.tooltipTweaks.rngMeterProfitPerUnit) {
			String formatCoinsPer = getFormatCoinsPer(stack, needed, gainPerRun, labelSingular);
			if (formatCoinsPer != null) {
				toolTip.add("   " + formatCoinsPer);
			}
		}

		toolTip.add(" ");
		if (progressString != null) {
			toolTip.add(progressString);
		}
	}

	private int getRepoScore(ItemStack stack, String repoCategory) {
		ItemResolutionQuery query =
			NotEnoughUpdates.INSTANCE.manager.createItemResolutionQuery().withItemStack(stack).withCurrentGuiContext();
		String internalName = query.resolveInternalName();

		JsonObject jsonObject = Constants.RNGSCORE;
		if (jsonObject == null) {
			Utils.showOutdatedRepoNotification();
			return -1;
		}

		String repoType = grabRepoType(stack);
		if (!jsonObject.has(repoCategory)) return -1;

		JsonObject category = jsonObject.get(repoCategory).getAsJsonObject();
		if (!category.has(repoType)) return -1;

		JsonObject typeObject = category.get(repoType).getAsJsonObject();
		if (!typeObject.has(internalName)) return -1;

		return typeObject.get(internalName).getAsInt();
	}

	// Determines the floor or the slayer type from where the item can be obtained. E.g. F7, M3, Revenant Horror or Sven Packmaster
	private String grabRepoType(ItemStack stack) {
		String openChestName = Utils.getOpenChestName();
		if (openChestName.contains("Catacombs")) {
			if (openChestName.equals("Catacombs RNG Meter")) {
				List<String> list = ItemUtils.getLore(stack);
				String line = list.get(4);
				return line.substring(26, 28);
			} else {
				// supporting more pages (f7/m7)
				if (openChestName.contains("(")) {
					return openChestName.substring(17, 19);
				}
				return openChestName.substring(11, 13);
			}
		} else {
			return openChestName.substring(0, openChestName.length() - 9);
		}
	}

	private void handleArrowKeys(Map<String, Integer> runsData) {
		boolean left = Keyboard.isKeyDown(Keyboard.KEY_LEFT);
		boolean right = Keyboard.isKeyDown(Keyboard.KEY_RIGHT);
		if (!pressedArrowLast && (left || right)) {
			if (Utils.getOpenChestName().contains("Catacombs") ? right : left) {
				currentSelected--;
			} else {
				currentSelected++;
			}
			if (currentSelected < 0) currentSelected = 0;
			if (currentSelected >= runsData.size()) currentSelected = runsData.size() - 1;
		}
		pressedArrowLast = left || right;
	}

	private int calculateChance(String string) {
		return (int) (100.0 / Double.parseDouble(string));
	}
}
