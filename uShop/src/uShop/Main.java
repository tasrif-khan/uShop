package uShop;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.SimplePluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.milkbowl.vault.economy.Economy;

public class Main extends JavaPlugin {

	/*
	 * Vault Economy plugin
	 */
	private Economy economy = null;

	/*
	 * Main config.yml
	 */
	private FileConfiguration cfg;

	/*
	 * Inventories that are currently open
	 */
	private HashMap<Player, Inventory> openShops = new HashMap<Player, Inventory>();

	/*
	 * List that contains all information about sell items
	 */
	private List<CustomItem> customItems = new ArrayList<CustomItem>();
	
	/*
	 * Gson object for serializing processes
	 */
	
	private Gson gson = new Gson();

	@Override
	public void onEnable() {
		setupEconomy();
		
		this.saveDefaultConfig();
		this.cfg = this.getConfig();
		
		if(this.cfg.getString("sell-prices") != null) {
			customItems = gson.fromJson(cfg.getString("sell-prices"), new TypeToken<List<CustomItem>>(){}.getType());
		}

		// registering command
		registerCommand(this.cfg.getString("command"));
		
		this.getCommand("ushop").setExecutor(new uShopCmd(this));

		this.getServer().getPluginManager().registerEvents(new Listeners(this), this);

		final String itemEnumFormat = cfg.getString("gui-item-enumeration-format").replace("&", "§");

		this.getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
			Iterator<Player> it = openShops.keySet().iterator();
			while (it.hasNext()) {
				Player p = it.next();
				if (p.getOpenInventory().getTopInventory() != null) {
					if (p.getOpenInventory().getTopInventory().getTitle() != null) {
						if (p.getOpenInventory().getTopInventory().getTitle()
								.equals(cfg.getString("gui-name").replace("&", "§"))) {
							
							// Aktuallisieren
							ItemStack[] invContent = p.getOpenInventory().getTopInventory().getContents();
							invContent[p.getOpenInventory().getTopInventory().getSize() - 5] = null;
							
							List<String> lore = new ArrayList<String>();
							double[] totalPrice = {0d};
							
							getSalableItems(invContent).forEach((item, amount) -> {
								double totalStackPrice = item.getPrice() * amount;
								totalPrice[0] += totalStackPrice;
								String s = itemEnumFormat.replace("%amount%", amount + "")
										.replace("%material%", item.getMaterial().toLowerCase().replace("_", " "))
										.replace("%price%", economy.format(item.getPrice()));
								lore.add(s);
							});

							ItemStack sell = p.getOpenInventory().getTopInventory()
									.getItem(p.getOpenInventory().getTopInventory().getSize() - 5);
							if (sell == null)
								continue;
							if (sell.getItemMeta() == null)
								continue;
							ItemMeta im = sell.getItemMeta();
							im.setDisplayName(cfg.getString("gui-sellitem.displayname").replace('&', '§')
									.replace("%total%", economy.format(totalPrice[0])));
							im.setLore(lore);
							sell.setItemMeta(im);

							p.getOpenInventory().getTopInventory()
									.setItem(p.getOpenInventory().getTopInventory().getSize() - 5, sell);
						} else {
							ItemStack[] stacks = openShops.get(p).getContents();
							stacks[openShops.get(p).getSize() - 5] = null;
							addToInv(p.getInventory(), stacks);
							openShops.remove(p);
						}

					}
				}
			}
		}, 20L, 20L);
	}

	public void saveMainConfig() {
		cfg.set("sell-prices", gson.toJson(customItems));
		this.saveConfig();
	}

	public void addToInv(Inventory inv, ItemStack[] is) {
		for (ItemStack stack : is) {
			if (stack != null) {
				inv.addItem(stack);
			}
		}
	}

	public HashMap<CustomItem, Integer> getSalableItems(ItemStack[] is) {
		HashMap<CustomItem, Integer> customItemsMap = new HashMap<CustomItem, Integer>();
		for (ItemStack stack : is) {
			if (stack != null) {
				// check if item is in the custom item list
				Optional<CustomItem> opt = customItems.stream().findFirst().filter((item) -> item.matches(stack));
				if(opt.isPresent()) {
					// add item to map
					customItemsMap.compute(opt.get(), (k, v) -> v == null ? stack.getAmount() : v + stack.getAmount());
				}
		
			}
		}
		return customItemsMap;
	}
	
	public double calcWorthOfContent(ItemStack[] content) {
		HashMap<CustomItem, Integer> salable = getSalableItems(content);
		return salable.keySet().stream().mapToDouble(v -> v.getPrice() * salable.get(v)).sum();
	}
	
	public boolean isSalable(ItemStack is) {
		return customItems.stream().anyMatch(v -> v.matches(is));
	}

	public Economy getEconomy() {
		return economy;
	}

	public HashMap<Player, Inventory> getOpenShops() {
		return openShops;
	}

	public List<CustomItem> getCustomItems() {
		return customItems;
	}

	public void registerCommand(String cmdLabel) {

		try {
			final Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
			// remove old command if already used
			SimplePluginManager spm = (SimplePluginManager) this.getServer().getPluginManager();
			Field f = SimplePluginManager.class.getDeclaredField("commandMap");
			f.setAccessible(true);
			SimpleCommandMap scm = (SimpleCommandMap) f.get(spm);
			
			Field f2 = SimpleCommandMap.class.getDeclaredField("knownCommands");
			f2.setAccessible(true);
			HashMap<String, Command> map = (HashMap<String, Command>) f2.get(scm);
			map.remove(cmdLabel);

			f.setAccessible(false);

			// register
			bukkitCommandMap.setAccessible(true);
			CommandMap commandMap = (CommandMap) bukkitCommandMap.get(Bukkit.getServer());
			Cmd cmd = new Cmd(this, cmdLabel);
			commandMap.register(cmdLabel, cmd);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	boolean setupEconomy() {
		RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager()
				.getRegistration(net.milkbowl.vault.economy.Economy.class);
		if (economyProvider != null) {
			economy = economyProvider.getProvider();
		}
		return (economy != null);
	}

	public void addCustomItem(CustomItem i) {
		customItems.add(i);
	}
}
