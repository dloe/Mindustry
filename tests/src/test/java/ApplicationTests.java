import arc.*;
import arc.backend.headless.*;
import arc.files.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.io.*;
import arc.util.serialization.*;
import arc.util.serialization.JsonValue.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.*;
import mindustry.ctype.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.g3d.*;
import mindustry.io.*;
import mindustry.io.SaveIO.*;
import mindustry.maps.*;
import mindustry.mod.*;
import mindustry.mod.Mods.*;
import mindustry.net.*;
import mindustry.net.Packets.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.liquid.*;
import mindustry.world.blocks.payloads.*;
import mindustry.world.blocks.storage.*;
import mindustry.world.blocks.defense.turrets.*;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.storage.CoreBlock.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.*;
import org.junit.jupiter.params.provider.*;
import org.mockito.*;

import java.io.*;
import java.nio.*;

import static mindustry.Vars.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.*;
import static org.mockito.Mockito.*;

public class ApplicationTests{
    static Map testMap;
    static Map winMap;
    static boolean initialized;
    //core/assets
    static final Fi testDataFolder = new Fi("../../tests/build/test_data");

    @BeforeAll
    public static void launchApplication(){
        launchApplication(true);
    }

    public static void launchApplication(boolean clear){
        //only gets called once
        if(initialized) return;
        initialized = true;

        try{
            boolean[] begins = {false};
            Throwable[] exceptionThrown = {null};
            Log.useColors = false;

            ApplicationCore core = new ApplicationCore(){
                @Override
                public void setup(){
                    //clear older data
                    if(clear){
                        ApplicationTests.testDataFolder.deleteDirectory();
                    }

                    Core.settings.setDataDirectory(testDataFolder);
                    headless = true;
                    net = new Net(null);
                    tree = new FileTree();
                    Vars.init();
                    world = new World(){
                        @Override
                        public float getDarkness(int x, int y){
                            //for world borders
                            return 0;
                        }
                    };
                    content.createBaseContent();
                    mods.loadScripts();
                    content.createModContent();

                    add(logic = new Logic());
                    add(netServer = new NetServer());

                    content.init();

                    mods.eachClass(Mod::init);

                    if(mods.hasContentErrors()){
                        for(LoadedMod mod : mods.list()){
                            if(mod.hasContentErrors()){
                                for(Content cont : mod.erroredContent){
                                    throw new RuntimeException("error in file: " + cont.minfo.sourceFile.path(), cont.minfo.baseError);
                                }
                            }
                        }
                    }

                }

                @Override
                public void init(){
                    super.init();
                    begins[0] = true;
                    testMap = maps.loadInternalMap("groundZero");
                    Thread.currentThread().interrupt();
                }
            };

            new HeadlessApplication(core, throwable -> exceptionThrown[0] = throwable);

            while(!begins[0]){
                if(exceptionThrown[0] != null){
                    fail(exceptionThrown[0]);
                }
                Thread.sleep(10);
            }

            Block block = content.getByName(ContentType.block, "build2");
            assertEquals("build2", block == null ? null : block.name, "2x2 construct block doesn't exist?");
        }catch(Throwable r){
            fail(r);
        }
    }

    @BeforeEach
    void resetWorld(){
        Time.setDeltaProvider(() -> 1f);
        logic.reset();
        state.set(State.menu);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
    "a",
    "asd asd asd asd asdagagasasjakbgeah;jwrej 23424234",
    "这个服务器可以用自己的语言说话",
    "\uD83D\uDEA3"
    })
    void writeStringTest(String string){
        ByteBuffer buffer = ByteBuffer.allocate(500);
        TypeIO.writeString(buffer, string);
        buffer.position(0);
        assertEquals(TypeIO.readString(buffer), string);

        ByteArrayOutputStream ba = new ByteArrayOutputStream();

        TypeIO.writeString(new Writes(new DataOutputStream(ba)), string);
        assertEquals(TypeIO.readString(new Reads(new DataInputStream(new ByteArrayInputStream(ba.toByteArray())))), string);

        SendChatMessageCallPacket pack = new SendChatMessageCallPacket();
        pack.message = string;

        buffer.position(0);
        pack.write(new Writes(new ByteBufferOutput(buffer)));
        int len = buffer.position();
        buffer.position(0);
        pack.message = "INVALID";
        pack.read(new Reads(new ByteBufferInput(buffer)), len);
        pack.handled();

        assertEquals(string, pack.message);

        buffer.position(0);
        Writes writes = new Writes(new ByteBufferOutput(buffer));
        TypeIO.writeString(writes, string);

        buffer.position(0);

        assertEquals(string, TypeIO.readString(new Reads(new ByteBufferInput(buffer))));

        buffer.position(0);
        ConnectPacket con = new ConnectPacket();
        con.name = string;
        con.uuid = "AAAAAAAA";
        con.usid = "AAAAAAAA";
        con.mods = new Seq<>();
        con.write(new Writes(new ByteBufferOutput(buffer)));

        con.name = "INVALID";
        buffer.position(0);
        con.read(new Reads(new ByteBufferInput(buffer)));

        assertEquals(string, con.name);
    }

    @Test
    void writeRules(){
        ByteBuffer buffer = ByteBuffer.allocate(1000);

        Rules rules = new Rules();
        rules.attackMode = true;
        rules.buildSpeedMultiplier = 99f;

        TypeIO.writeRules(new Writes(new ByteBufferOutput(buffer)), rules);
        buffer.position(0);
        Rules res = TypeIO.readRules(new Reads(new ByteBufferInput(buffer)));

        assertEquals(rules.buildSpeedMultiplier, res.buildSpeedMultiplier);
        assertEquals(rules.attackMode, res.attackMode);
    }

    @Test
    void writeRules2(){
        Rules rules = new Rules();
        rules.attackMode = true;
        rules.tags.put("blah", "bleh");
        rules.buildSpeedMultiplier = 99.1f;

        String str = JsonIO.write(rules);
        Rules res = JsonIO.read(Rules.class, str);

        assertEquals(rules.buildSpeedMultiplier, res.buildSpeedMultiplier);
        assertEquals(rules.attackMode, res.attackMode);
        assertEquals(rules.tags.get("blah"), res.tags.get("blah"));

        String str2 = JsonIO.write(new Rules(){{
            attackMode = true;
        }});
    }

    @Test
    void serverListJson(){
        String[] files = {"servers_v6.json", "servers_v7.json", "servers_be.json"};


        for(String file : files){
            try{
                String str = Core.files.absolute("./../../" + file).readString();
                assertEquals(ValueType.array, new JsonReader().parse(str).type());
                assertTrue(Jval.read(str).isArray());
                JSONArray array = new JSONArray(str);
                assertTrue(array.length() > 0);
            }catch(Exception e){
                fail("Failed to parse " + file, e);
            }
        }
    }

    @Test
    void initialization(){
        assertNotNull(logic);
        assertNotNull(world);
        assertTrue(content.getContentMap().length > 0);
    }

    @Test
    void playMap(){
        world.loadMap(testMap);
    }

    @Test
    void spawnWaves(){
        world.loadMap(testMap);
        assertTrue(spawner.countSpawns() > 0, "No spawns present.");
        logic.runWave();
        //force trigger delayed spawns
        Time.setDeltaProvider(() -> 1000f);
        Time.update();
        Time.update();
        Groups.unit.update();
        assertFalse(Groups.unit.isEmpty(), "No enemies spawned.");
    }

    @Test
    void runSectorCaptured(){
        int waveCount = 0;
        testMap = maps.loadInternalMap("nuclearComplex");
        world.loadMap(testMap);
        while (waveCount < 100){
            logic.runWave();
            boolean boss = state.rules.spawns.contains(group -> group.getSpawned(state.wave - 2) > 0 && group.effect == StatusEffects.boss);
            if (boss){
                System.out.println("BOSS ROUND"); //nuclear complex round 50 eclipse boss spawns.
                assertTrue(state.rules.waves); // there are still more waves of enemies. game not finished yet
                int afterBoss = 0;
                while (afterBoss < 3){
                    if(state.rules.waves && (state.enemies == 0 && state.rules.winWave > 0 && state.wave >= state.rules.winWave && !spawner.isSpawning()) ||
                            (state.rules.attackMode && state.rules.waveTeam.cores().isEmpty())){
                        Call.sectorCapture();
                    }
                    Groups.unit.update();
                    afterBoss++;
                }
            }
            Groups.unit.update();
            Groups.clear();
            waveCount++;
        }
        Call.sectorCapture();
        assertFalse(state.rules.waves); //state.rules.waves now being false means sector has been captured and game is over
    }

    /** Run waves until boss wave is reached to make sure we get there. the boss wave will be run before exiting loop. */
    @Test
    void runBossWave(){
        testMap = maps.loadInternalMap("nuclearComplex");
        world.loadMap(testMap);
        while (true)
        {
            logic.runWave();
            boolean boss = state.rules.spawns.contains(group -> group.getSpawned(state.wave - 2) > 0 && group.effect == StatusEffects.boss);
            if (boss)
            {
                //nuclear complex round 50 eclipse boss spawns.
                assertTrue(boss, "boss wave assertion");
                Groups.unit.update();
                break;
            }
            Groups.unit.update();
        }
    }

    /** Start wave and check if we have enemies, and we are no longer in prep phase */
    @Test
    void WavesActiveStateTest(){
        world.loadMap(testMap);
        //increments the wave number
        logic.runWave();

        Groups.unit.update();
        //enemies present and spawned. No longer in prep phase. Wave has started.
        assertFalse(Groups.unit.isEmpty(), "Enemies spawned.");
    }

    /** Checking for Wave countdown state (aka our wave timer before the wave starts) */
    @Test
    void WaveCountdownStateTest(){
        world.loadMap(testMap);
        logic.runWave();
        //force trigger delayed spawns
        Time.setDeltaProvider(() -> 1000f);
        Time.update();
        Time.update();
        Groups.unit.update();
        //check if we do not have any enemies spawning, and we have a Wave Timer active
        //waveTimer (if true we have a wavetime aka (int)(state.wavetime/60)
        assertFalse(Groups.unit.isEmpty() && !state.rules.waveTimer, "Wave Countdown Active.");
    }

    /** Start wave and kill player's core. */
    @Test
    void GameOverStateTest(){
        world.loadMap(testMap);
        //to make this a campaign map we need a sector - make a dummy sector
        state.rules.sector = new Sector(state.rules.planet, PlanetGrid.Ptile.empty);
        state.rules.winWave = 50;

        //increments the wave number
        logic.runWave();
        Groups.unit.update();

        //get our player core and kill it
        for (CoreBuild core :  state.teams.playerCores()){
            core.kill();
        }
        //need to set state to is playing to see if we are in fact dead, otherwise we are inside a menu
        state.set(State.playing);
        logic.update();

        //if we die or loose, state.gameOver is now true
        //enemies present and spawned. No longer in prep phase. Wave has started.
        assertTrue(state.gameOver, "Game Over status reached.");
    }

    /** Checks if we reach captured sector status */
    @Test
    void SectorCapturedStateTest(){
        //nuclear complex round 50 eclipse boss spawns.
        testMap = maps.loadInternalMap("nuclearComplex");

        world.loadMap(testMap);
        //need to add a sector to our rules, so we know this is a campaign mission
        state.rules.sector = new Sector(state.rules.planet, PlanetGrid.Ptile.empty);
        state.rules.winWave = 50;
        boolean boss = false;
        while (!boss){
            logic.runWave();
            boss = state.rules.spawns.contains(group -> group.getSpawned(state.wave - 2) > 0 && group.effect == StatusEffects.boss);
            Groups.unit.update();
        }
        //when killing all the enemies, the wave is over therefore we must reset for the next wave.
        Groups.clear();
        //reset for next wave (since we are not naturally killing everything)
        spawner.reset();
        state.set(State.playing);
        logic.update();
        assertTrue(state.rules.sector.info.wasCaptured, "Was Captured");
    }

    /** Update fog of war and verify it is functioning or active in game */
    @Test
    void FogOfWarUpdateTest(){
        state.set(State.playing);
        testMap = maps.loadInternalMap("onset");
        world.loadMap(testMap);
        //set rule to have fog of war to true
        state.rules.fog = true;
        //Can set it to be a specific sector, onset for example is on Erekir
        state.rules.sector = new Sector(state.rules.planet, PlanetGrid.Ptile.empty);
        //set planet to erekir which has fog of war active (needs constant static planet Erekir)
        state.rules.planet = Planets.erekir;

        //run an update and verify our state.rules.fog = true
        logic.update();
        assertTrue(state.rules.fog, "Fog of war active");
    }

    /** Make building, damage building and compare health to see it went through */
    @Test
    void BuildingDamageTest(){
        initBuilding();

        Builderc d1 = UnitTypes.poly.create(Team.sharded);
        d1.set(10f, 20f);
        d1.addBuild(new BuildPlan(0, 0, 0, Blocks.copperWallLarge));

        Time.setDeltaProvider(() -> 3f);
        d1.update();
        Time.setDeltaProvider(() -> 1f);

        assertEquals(content.getByName(ContentType.block, "build2"), world.tile(0, 0).block());
        Time.setDeltaProvider(() -> 9999f);

        //prevents range issues
        state.rules.infiniteResources = true;
        d1.update();
        assertEquals(Blocks.copperWallLarge, world.tile(0, 0).block());

        d1.addBuild(new BuildPlan(0, 0));
        d1.damage(10000); //attack and destroy the building
        d1.update();

        assertEquals(Blocks.air, world.tile(0, 0).block());
    }

    @Test
    void verifyWorldCreation(){
        Tiles tiles = world.resize(8, 8);
        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();

        for(Tile tile : world.tiles){
            assertEquals(tile.data, 0);
        }
    }

    @Test
    void createMap(){
        Tiles tiles = world.resize(8, 8);

        world.beginMapLoad();
        tiles.fill();
        world.endMapLoad();
    }

    /** Creates a map (8 by 8 tileset) and assigns a new coreShard building to coordinates 4,4. Then Verifies block is created successfully */
    @Test
    void multiblock(){
        createMap();
        int bx = 4;
        int by = 4;
        //create a coreShard block at this tile location of cordinate (4,4)
        //Default team for single player is automatically team sharded
        world.tile(bx, by).setBlock(Blocks.coreShard, Team.sharded, 0);
        //check if created block we just made is created properly
        assertEquals(world.tile(bx, by).team(), Team.sharded);
        //Runs through each coordinate starting at 4,4 to verify they are core shard
        for(int x = bx - 1; x <= bx + 1; x++){
            for(int y = by - 1; y <= by + 1; y++){
                assertEquals(world.tile(x, y).block(), Blocks.coreShard);
                assertEquals(world.tile(x, y).build, world.tile(bx, by).build);
            }
        }
    }

    /** Gives/Removes a specific tile resources and verify the resources were properly assigned */
    @Test
    void blockInventories(){
        multiblock();
        //grab tile of our world at coordinate 4,4
        Tile tile = world.tile(4, 4);
        tile.build.items.add(Items.coal, 5);
        tile.build.items.add(Items.titanium, 50);
        assertEquals(tile.build.items.total(), 55);
        tile.build.items.remove(Items.phaseFabric, 10);
        tile.build.items.remove(Items.titanium, 10);
        assertEquals(tile.build.items.total(), 45);
    }

    /** Gives/Removes a specific tile resources and verify the resources were properly assigned */
    @Test
    void addingEntireTileContentsTile(){
        multiblock();
        //grab tile of our world at coordinate 4,4
        Tile tile = world.tile(4, 4);

        //set up a tile 2 as well
        Tile tile2 = world.tile(0, 0);
        world.tile(0, 0).setBlock(Blocks.coreShard, Team.sharded, 0);
        //Adding contents of one tile to another (our total)
        tile2.build.items.add(Items.titanium, 50);

        //sanity check to ensure we have not created the same tile twice
        assertNotSame(tile, tile2);

        tile.build.items.add(Items.coal, 5);
        //add our tile2 items to tile1
        //adds an extra 55 (50 in one and 5 in the other) to our total (remember between both tiles its shared)
        tile.build.items.add(tile2.build.items);
        assertEquals(tile.build.items.total(), 110);
    }

    /** Add an item and test its ID to ensure proper assignment and Check if coal ID is properly read */
    @Test
    void itemModuleIDCoal(){
        multiblock();
        Tile tile = world.tile(1, 1);
        world.tile(1, 1).setBlock(Blocks.coreShard, Team.sharded, 0);
        tile.build.items.add(Items.coal, 5);

        assertTrue(tile.build.items.has(5), "Coal ID was found on tile");
    }

    /** Check if coal item is properly read */
    @Test
    void itemModuleByItemCoal(){
        //Add an item and test if the item lookup returns true to ensure proper assignment
        multiblock();
        Tile tile = world.tile(1, 1);
        world.tile(1, 1).setBlock(Blocks.coreShard, Team.sharded, 0);
        tile.build.items.add(Items.coal, 5);

        assertTrue(tile.build.items.has(Items.coal), "Coal Item was found on tile");
    }

    /** Add an item and use different method of removing. Remove items by ItemStack */
    @Test
    void itemStackRemoveAndVerifyCheck(){
        multiblock();
        Tile tile = world.tile(1, 1);
        world.tile(1, 1).setBlock(Blocks.coreShard, Team.sharded, 0);

        //make a new ItemStack[], stack of 5 copper stacks each of 100
        ItemStack[] stacks = new ItemStack[]{
            new ItemStack(Items.copper, 100), new ItemStack(Items.copper, 100),
            new ItemStack(Items.copper, 100), new ItemStack(Items.copper, 100),
            new ItemStack(Items.copper, 100)
        };

        tile.build.items.add(Items.copper, 600);
        //remove one stack of 100, passing this as a parameter in remove
        tile.build.items.remove(new ItemStack(Items.copper, 100));
        //check if we still have a stack of 500
        assertTrue(tile.build.items.has(stacks), "Coal Item was found on tile");
    }

    /** Create a turret, load it, check that loading caps at max ammo */
    @Test
    void testAmmoLoadingCap(){
        createMap();
        state.set(State.playing);

        // Create duo turret block at 0,1
        Tile tileDuo = world.rawTile(0, 1);
        tileDuo.setBlock(Blocks.duo, Team.sharded);

        // Create copper source at 0,0
        Tile source = world.rawTile(0,0);
        source.setBlock(Blocks.itemSource, Team.sharded);
        source.build.configureAny(Items.copper);

        // Check that ammo is initially 0
        assertEquals(0, ((ItemTurret.ItemTurretBuild) tileDuo.build).totalAmmo,
            "Newly built turret ammo is not 0");

        // Allow turret to load from ammo sources
        updateBlocks(100);
        
        // Check ammo amount is capped at maxAmmo
        assertEquals(((ItemTurret) tileDuo.block()).maxAmmo, ((ItemTurret.ItemTurretBuild) tileDuo.build).totalAmmo,
            "Duo is not fully loaded");

        // Allow more loading time
        updateBlocks(100);

        // Check ammo amount is still capped at maxAmmo
        assertEquals(((ItemTurret) tileDuo.block()).maxAmmo, ((ItemTurret.ItemTurretBuild) tileDuo.build).totalAmmo,
            "Duo ammo did not stay at cap");
    }

    /** Check that pneumatic drill mined more items than mechanical drill */
    @Test
    void testDrillTypes(){
        createMap();
        state.set(State.playing);

        // Create sand resource tiles
        Tile tile00 = world.rawTile(0,0);
        Tile tile01 = world.rawTile(0,1);
        Tile tile10 = world.rawTile(1,0);
        Tile tile11 = world.rawTile(1,1);
        Tile tile20 = world.rawTile(2,0);
        Tile tile21 = world.rawTile(2,1);
        Tile tile30 = world.rawTile(3,0);
        Tile tile31 = world.rawTile(3,1);
        tile00.setFloor((Floor)Blocks.sand);
        tile01.setFloor((Floor)Blocks.sand);
        tile10.setFloor((Floor)Blocks.sand);
        tile11.setFloor((Floor)Blocks.sand);
        tile20.setFloor((Floor)Blocks.sand);
        tile21.setFloor((Floor)Blocks.sand);
        tile30.setFloor((Floor)Blocks.sand);
        tile31.setFloor((Floor)Blocks.sand);

        //Create 2 different types of drills on top of sand resource tiles with adjacent storage containers
        // Create mechanical drill at 0,0
        tile00.setBlock(Blocks.mechanicalDrill, Team.sharded);

        // Create pneumatic drill at 2,0
        tile20.setBlock(Blocks.pneumaticDrill, Team.sharded);

        // Create storage containers
        Tile container1 = world.rawTile(0,2);
        Tile container2 = world.rawTile(2,2);
        container1.setBlock(Blocks.container, Team.sharded);
        container2.setBlock(Blocks.container, Team.sharded);

        updateBlocks(2000);

        //Check that both drills are mining items
        assertTrue(container1.build.items.has(Items.sand),"Mechanical drill did not mine any items");
        assertTrue(container2.build.items.has(Items.sand),"Pneumatic drill did not mine any items");

        // Test that pneumatic drill has mined more items than mechanical drill
        assertTrue(container2.build.items.total() > container1.build.items.total(),
            "Pneumatic drill did not mine more items than mechanical drill.");
    }

    /** The drill with the water source should mine faster than the drill without water.*/
    @Test
    void testDrillWithWater(){
        createMap();
        state.set(State.playing);

        // Create sand resource tiles
        Tile tile00 = world.rawTile(0,0);
        Tile tile01 = world.rawTile(0,1);
        Tile tile10 = world.rawTile(1,0);
        Tile tile11 = world.rawTile(1,1);
        Tile tile20 = world.rawTile(2,0);
        Tile tile21 = world.rawTile(2,1);
        Tile tile30 = world.rawTile(3,0);
        Tile tile31 = world.rawTile(3,1);
        tile00.setFloor((Floor)Blocks.sand);
        tile01.setFloor((Floor)Blocks.sand);
        tile10.setFloor((Floor)Blocks.sand);
        tile11.setFloor((Floor)Blocks.sand);
        tile20.setFloor((Floor)Blocks.sand);
        tile21.setFloor((Floor)Blocks.sand);
        tile30.setFloor((Floor)Blocks.sand);
        tile31.setFloor((Floor)Blocks.sand);

        // Create 2 drills of the same type, one with a water source and one without a water source.
        // Create mechanical drill at 0,0
        tile00.setBlock(Blocks.mechanicalDrill, Team.sharded);

        // Create mechanical drill at 2,0
        tile20.setBlock(Blocks.mechanicalDrill, Team.sharded);

        // Create storage containers
        Tile container1 = world.rawTile(0,2);
        Tile container2 = world.rawTile(2,2);
        container1.setBlock(Blocks.container, Team.sharded);
        container2.setBlock(Blocks.container, Team.sharded);

        // Create water source
        Tile waterSource = world.rawTile(4,0);
        waterSource.setBlock(Blocks.liquidSource, Team.sharded);
        waterSource.build.configureAny(Liquids.water);

        updateBlocks(2000);

        // Test that drill with water has mined more items than drill without water
        assertTrue(container2.build.items.total() > container1.build.items.total(),
            "Drill with water did not mine more items than drill without water.");
    }

    /** Check that both drills are mining items and sitting on 4 resource tiles mined more than the drill on 2 resource tiles */
    @Test
    void testDrillOres(){
        createMap();
        state.set(State.playing);

        // Create resource tiles
        Tile tile00 = world.rawTile(0,0);
        Tile tile01 = world.rawTile(0,1);
        Tile tile10 = world.rawTile(1,0);
        Tile tile11 = world.rawTile(1,1);
        Tile tile20 = world.rawTile(2,0);
        Tile tile21 = world.rawTile(2,1);
        Tile tile30 = world.rawTile(3,0);
        Tile tile31 = world.rawTile(3,1);
        tile00.setFloor((Floor)Blocks.dirt);
        tile01.setFloor((Floor)Blocks.dirt);
        tile10.setFloor((Floor)Blocks.dirt);
        tile11.setFloor((Floor)Blocks.dirt);
        tile20.setFloor((Floor)Blocks.dirt);
        tile21.setFloor((Floor)Blocks.dirt);
        tile30.setFloor((Floor)Blocks.dirt);
        tile31.setFloor((Floor)Blocks.dirt);
        tile00.setOverlay(Blocks.oreCopper);
        tile01.setOverlay(Blocks.oreCopper);
        tile20.setOverlay(Blocks.oreCopper);
        tile21.setOverlay(Blocks.oreCopper);
        tile30.setOverlay(Blocks.oreCopper);
        tile31.setOverlay(Blocks.oreCopper);

        //Create 2 drills of the same type on top of different numbers of resource tiles with adjacent storage containers
        // Create pneumatic drills
        tile00.setBlock(Blocks.pneumaticDrill, Team.sharded);
        tile20.setBlock(Blocks.pneumaticDrill, Team.sharded);

        // Create storage containers
        Tile container1 = world.rawTile(0,2);
        Tile container2 = world.rawTile(2,2);
        container1.setBlock(Blocks.container, Team.sharded);
        container2.setBlock(Blocks.container, Team.sharded);

        updateBlocks(2000);

        // Test that drills successfully mined items
        assertTrue(container1.build.items.has(Items.copper) && container2.build.items.has(Items.copper),
            "Drill(s) did not mine any items");

        // Test that drill on 4 copper tiles has mined more items than drill on 2 copper tiles
        assertTrue(container2.build.items.total() > container1.build.items.total(),
            "Pneumatic drill did not mine more items than mechanical drill.");
    }

    /** Check that all 7 fed containers have proper coal */
    @Test
    void testDistributor(){
        createMap();
        state.set(State.playing);

        // Create tiles
        Tile source = world.rawTile(3, 2);
        Tile distributor = world.rawTile(3,3);
        //Create 7 conveyor belts leading to 7 containers from the distributor
        Tile belt1 = world.rawTile(2,3);
        Tile belt2 = world.rawTile(2,4);
        Tile belt3 = world.rawTile(3,5);
        Tile belt4 = world.rawTile(4,5);
        Tile belt5 = world.rawTile(5,4);
        Tile belt6 = world.rawTile(5,3);
        Tile belt7 = world.rawTile(4,2);
        Tile container1 = world.rawTile(0,2);
        Tile container2 = world.rawTile(0,4);
        Tile container3 = world.rawTile(2,6);
        Tile container4 = world.rawTile(4,6);
        Tile container5 = world.rawTile(6,4);
        Tile container6 = world.rawTile(6,2);
        Tile container7 = world.rawTile(4,0);

        // Create buildings
        source.setBlock(Blocks.itemSource, Team.sharded);
        source.build.configureAny(Items.coal);
        //Create a distributor with a coal source adjacent
        distributor.setBlock(Blocks.distributor, Team.sharded);
        belt1.setBlock(Blocks.titaniumConveyor, Team.sharded, 2);
        belt2.setBlock(Blocks.titaniumConveyor, Team.sharded, 2);
        belt3.setBlock(Blocks.titaniumConveyor, Team.sharded, 1);
        belt4.setBlock(Blocks.titaniumConveyor, Team.sharded, 1);
        belt5.setBlock(Blocks.titaniumConveyor, Team.sharded, 0);
        belt6.setBlock(Blocks.titaniumConveyor, Team.sharded, 0);
        belt7.setBlock(Blocks.titaniumConveyor, Team.sharded, 3);
        container1.setBlock(Blocks.container, Team.sharded);
        container2.setBlock(Blocks.container, Team.sharded);
        container3.setBlock(Blocks.container, Team.sharded);
        container4.setBlock(Blocks.container, Team.sharded);
        container5.setBlock(Blocks.container, Team.sharded);
        container6.setBlock(Blocks.container, Team.sharded);
        container7.setBlock(Blocks.container, Team.sharded);

        updateBlocks(200);

        assertTrue(container1.build.items.has(Items.coal) &&
            container2.build.items.has(Items.coal) &&
            container3.build.items.has(Items.coal) &&
            container4.build.items.has(Items.coal) &&
            container5.build.items.has(Items.coal) &&
            container6.build.items.has(Items.coal) &&
            container7.build.items.has(Items.coal),
           "Not all containers have items.");
    }

    /**
     * Container with an adjacent copper source and Unloader adjacent to the 1st container, with a conveyor leading to a 2nd container
     * Check that the 2nd container gets loaded
     */
    @Test
    void testUnloader(){
        createMap();
        state.set(State.playing);

        // Create tiles and buildings
        world.tile(0,0).setBlock(Blocks.itemSource, Team.sharded);
        world.tile(0,0).build.configureAny(Items.copper);
        world.tile(1,0).setBlock(Blocks.container, Team.sharded);
        world.tile(3,0).setBlock(Blocks.unloader, Team.sharded);
        world.tile(4,0).setBlock(Blocks.conveyor, Team.sharded, 0);
        world.tile(5,0).setBlock(Blocks.container, Team.sharded);

        updateBlocks(200);

        assertTrue(world.tile(5,0).build.items.has(Items.copper),"2nd container is empty");
    }

    /** Ensure overflow gate in between ItemSources have proper loads */
    @Test
    void testOverflowGate(){
        createMap();
        state.set(State.playing);

        // Create tiles and buildings
        // Create an item source leading to a duo turret with an overflow gate in between.
        world.tile(0,0).setBlock(Blocks.itemSource, Team.sharded);
        world.tile(0,0).build.configureAny(Items.copper);
        world.tile(0,1).setBlock(Blocks.titaniumConveyor, Team.sharded, 1);
        world.tile(0,2).setBlock(Blocks.overflowGate, Team.sharded);
        world.tile(0,3).setBlock(Blocks.titaniumConveyor, Team.sharded, 1);
        world.tile(0,4).setBlock(Blocks.duo, Team.sharded);
        world.tile(1,2).setBlock(Blocks.titaniumConveyor, Team.sharded, 0);
        // Create a container to the right of the overflow gate.
        world.tile(2,2).setBlock(Blocks.container, Team.sharded);

        //Once the duo is full and the items back up, the overflow gate should route items to the container.
        updateBlocks(20);
        // Check 2nd container doesn't have copper yet
        assertFalse(world.tile(2,2).build.items.has(Items.copper),"2nd container has copper prematurely");

        updateBlocks(200);
        // Check 2nd container has copper, container gets loaded.
        assertTrue(world.tile(2,2).build.items.has(Items.copper),"2nd container is empty");
    }

    /** Now using dummy method to always get optimal flow with pipping */
    @Test
    void liquidJunctionOutputTestability(){
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;

        Tile source = world.rawTile(0, 0), tank = world.rawTile(1, 4),
            junction = world.rawTile(0, 1), conduit = world.rawTile(0, 2);

        source.setBlock(Blocks.liquidSource, Team.sharded);
        source.build.configureAny(Liquids.water);

        junction.setBlock(Blocks.liquidJunction, Team.sharded);

        conduit.setBlock(Blocks.conduit, Team.sharded, 1);

        tank.setBlock(Blocks.liquidTank, Team.sharded);

        // New tile updates
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.isCenter()){
                tile.build.updateProximity();
            }
        }
        for(int i = 0; i < 20; i++){
            Time.update();
            for(Tile tile : world.tiles){
                if(tile.build instanceof Conduit.ConduitBuild){
                    ((Conduit.ConduitBuild) conduit.build).updateTileDummyFlow();
                }else{
                    if (tile.build != null && tile.isCenter()){
                        tile.build.update();
                    }
                }
            }
        }

        assertTrue(tank.build.liquids.currentAmount() >= 1, "Liquid not moved through junction");
        assertSame(tank.build.liquids.current(), Liquids.water, "Tank has no water");
    }


    /** Ensure game state is properly updated and reset */
    @Test
    void mockVerifyLogicGameStateReset(){
        //make mock of logic
        Logic oldLogic = logic;

        Logic logMock = Mockito.mock(Logic.class);
        logic = logMock;

        int waveCount = 0;
        testMap = maps.loadInternalMap("nuclearComplex");
        world.loadMap(testMap);
        //trigger and surpass boss wave, so we can meet parameters of a sector capture status
        //Any time game state is updated, it must be reset properly and that requires a reset to run
        //Involves calling an event to clear timers, stats and other info that needs to be updated
        while (waveCount < 100){
            logic.runWave();
            boolean boss = state.rules.spawns.contains(group -> group.getSpawned(state.wave - 2) > 0 &&
                    group.effect == StatusEffects.boss);
            if (boss){
                int afterBoss = 0;
                while (afterBoss < 3){
                    if(state.rules.waves && (state.enemies == 0 && state.rules.winWave > 0 && state.wave >=
                            state.rules.winWave && !spawner.isSpawning()) || (state.rules.attackMode &&
                            state.rules.waveTeam.cores().isEmpty())){
                        Call.sectorCapture();
                    }
                    Groups.unit.update();
                    afterBoss++;
                }
            }
            Groups.unit.update();
            Groups.clear();
            waveCount++;
        }
        Call.sectorCapture();

        //make sure reset to make sure game state is properly updated and reset. any time game state is updated, it
        // must be reset properly and that requires a reset to run. Involves calling an event to clear timers, stats and
        // other info that needs to be updated
        verify(logMock, atLeast(1)).reset();
        //ensure we return our original logic class to avoid failures in rest of test suite
        logic = oldLogic;
    }

    /** Testing how often certain methods are run in our logic class */
    @Test
    void logicMockTest(){
        Logic oldLogic = logic;

        Logic logMock = Mockito.mock(Logic.class);
        logic = logMock;

        world.loadMap(testMap);

        //to make this a campaign map we need a sector - make a dummy sector
        state.rules.sector = new Sector(state.rules.planet, PlanetGrid.Ptile.empty);
        state.rules.winWave = 50;

        //increments the wave number
        logic.runWave();
        Groups.unit.update();

        //get our player core and kill it
        for (CoreBuild core :  state.teams.playerCores()){
            core.kill();
        }
        //need to set state to is playing to see if we are in fact dead, otherwise we are inside a menu
        state.set(State.playing);
        logic.update();

        //test game over state and verify how often CheckGameState is run
        verify(logMock, atLeast(1)).update();

        //ensure logic remains for rest of test suite
        logic = oldLogic;
    }

    /** Build turret and verify correct procedure for method calls. */
    @Test
    void mockTurretBuildVerification(){
        createMap();
        state.set(State.playing);

        // Create duo turret block at 0,1
        Tile duoMock = Mockito.mock(Tile.class);
        duoMock.constructTile(world.rawTile(0, 1));
        duoMock.setBlock(Blocks.duo, Team.sharded);

        // Create copper source at 0,0
        //this source tile creation shouldn't impact the creation of our turret, should consistently
        //only require one call of setblocks for our turret
        Tile source = world.rawTile(0,0);
        source.setBlock(Blocks.itemSource, Team.sharded);
        source.build.configureAny(Items.copper);

        updateBlocks(100);

        //verify the duo Building uses proper setBlock for turent building
        verify(duoMock, atLeast(1)).setBlock(Blocks.duo, Team.sharded);
        //verify that tile is not static we create (if static then we have to recache for renderer)
        verify(duoMock, atLeast(0)).recache();
        verify(duoMock, atLeast(0)).recacheWall();
    }

    @Test
    void timers(){
        boolean[] ran = {false};
        Time.run(1.9999f, () -> ran[0] = true);

        Time.update();
        assertFalse(ran[0]);
        Time.update();
        assertTrue(ran[0]);
    }

    @Test
    void manyTimers(){
        int runs = 100000;
        int[] total = {0};
        for(int i = 0; i < runs; i++){
            Time.run(0.999f, () -> total[0]++);
        }
        assertEquals(0, total[0]);
        Time.update();
        assertEquals(runs, total[0]);
    }

    @Test
    void longTimers(){
        Time.setDeltaProvider(() -> Float.MAX_VALUE);
        Time.update();
        int steps = 100;
        float delay = 100000f;
        Time.setDeltaProvider(() -> delay / steps + 0.01f);
        int runs = 100000;
        int[] total = {0};
        for(int i = 0; i < runs; i++){
            Time.run(delay, () -> total[0]++);
        }
        assertEquals(0, total[0]);
        for(int i = 0; i < steps; i++){
            Time.update();
        }
        assertEquals(runs, total[0]);
    }

    @Test
    void save(){
        world.loadMap(testMap);
        assertTrue(state.teams.playerCores().size > 0);
        SaveIO.save(saveDirectory.child("0.msav"));
    }

    @Test
    void saveLoad(){
        world.loadMap(testMap);
        Map map = state.map;

        float hp = 30f;

        Unit unit = UnitTypes.dagger.spawn(Team.sharded, 20f, 30f);
        unit.health = hp;

        SaveIO.save(saveDirectory.child("0.msav"));
        resetWorld();
        SaveIO.load(saveDirectory.child("0.msav"));

        Unit spawned = Groups.unit.find(u -> u.type == UnitTypes.dagger);
        assertNotNull(spawned, "Saved daggers must persist");
        assertEquals(hp, spawned.health, "Spawned dagger health must save.");

        assertEquals(world.width(), map.width);
        assertEquals(world.height(), map.height);
        assertTrue(state.teams.playerCores().size > 0);
    }

    void updateBlocks(int times){
        for(Tile tile : world.tiles){
            if(tile.build != null && tile.isCenter()){
                tile.build.updateProximity();
            }
        }

        for(int i = 0; i < times; i++){
            Time.update();
            for(Tile tile : world.tiles){
                if(tile.build != null && tile.isCenter()){
                    tile.build.update();
                }
            }
        }
    }

    @Test
    void liquidOutput(){
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;

        world.tile(0, 0).setBlock(Blocks.liquidSource, Team.sharded);
        world.tile(0, 0).build.configureAny(Liquids.water);

        world.tile(2, 1).setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(10);

        assertTrue(world.tile(2, 1).build.liquids.currentAmount() >= 1);
        assertSame(world.tile(2, 1).build.liquids.current(), Liquids.water);
    }

    @Test
    void liquidJunctionOutput(){
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;

        Tile source = world.rawTile(0, 0), tank = world.rawTile(1, 4), junction = world.rawTile(0, 1), conduit = world.rawTile(0, 2);

        source.setBlock(Blocks.liquidSource, Team.sharded);
        source.build.configureAny(Liquids.water);

        junction.setBlock(Blocks.liquidJunction, Team.sharded);

        conduit.setBlock(Blocks.conduit, Team.sharded, 1);

        tank.setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(10);

        assertTrue(tank.build.liquids.currentAmount() >= 1, "Liquid not moved through junction");
        assertTrue(tank.build.liquids.current() == Liquids.water, "Tank has no water");
    }

    @Test
    void liquidRouterOutputAll() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source = world.rawTile(4,0), router = world.rawTile(4, 2), conduitUp1 = world.rawTile(4,1),
        conduitLeft = world.rawTile(3,2), conduitUp2 = world.rawTile(4, 3), conduitRight = world.rawTile(5, 2),
        leftTank = world.rawTile(1, 2), topTank = world.rawTile(4,5), rightTank = world.rawTile(7, 2);

        source.setBlock(Blocks.liquidSource, Team.sharded);
        source.build.configureAny(Liquids.water);
        conduitUp1.setBlock(Blocks.conduit, Team.sharded, 1);
        router.setBlock(Blocks.liquidRouter, Team.sharded);
        conduitLeft.setBlock(Blocks.conduit, Team.sharded,2);
        conduitUp2.setBlock(Blocks.conduit, Team.sharded, 1);
        conduitRight.setBlock(Blocks.conduit, Team.sharded, 0);
        leftTank.setBlock(Blocks.liquidTank, Team.sharded);
        topTank.setBlock(Blocks.liquidTank, Team.sharded);
        rightTank.setBlock(Blocks.liquidTank, Team.sharded);

        updateBlocks(200);
        assertTrue(rightTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to rightTank");
        assertTrue(topTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to topTank");
        assertTrue(leftTank.build.liquids.currentAmount() > 0, "Liquid router did not distribute to rightTank");
    }

    @Test
    void sorterOutputCorrect() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        //declaring a bunch of different objects/buildings/tiles/etc
        Tile source1 = world.rawTile(4, 0), source2 = world.rawTile(6, 0), s1conveyor = world.rawTile(4, 1),
        s2conveyor = world.rawTile(6, 1), s1s2conveyor = world.rawTile(5, 1), sorter = world.rawTile(5, 2),
        leftConveyor = world.rawTile(4, 2), rightConveyor = world.rawTile(6, 2), sortedConveyor = world.rawTile(5, 3),
        leftVault = world.rawTile(2, 2), rightVault = world.rawTile(8, 2), topVault = world.rawTile(5, 5);

        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        source2.setBlock(Blocks.itemSource, Team.sharded);
        source2.build.configureAny(Items.copper);
        s1conveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        s2conveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        s1s2conveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        sorter.setBlock(Blocks.sorter, Team.sharded);
        sorter.build.configureAny(Items.copper);
        leftConveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        rightConveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        sortedConveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        leftVault.setBlock(Blocks.vault, Team.sharded);
        rightVault.setBlock(Blocks.vault, Team.sharded);
        topVault.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, rightVault.build.items.first());
        assertEquals(Items.copper, topVault.build.items.first());
        assertEquals(Items.coal, leftVault.build.items.first());

    }

    @Test
    void routerOutputAll() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source1 = world.rawTile(5, 0),  conveyor = world.rawTile(5, 1),
        router = world.rawTile(5, 2), leftConveyor = world.rawTile(4, 2), rightConveyor = world.rawTile(6, 2),
        middleConveyor = world.rawTile(5, 3), leftVault = world.rawTile(2, 2),
        rightVault = world.rawTile(8, 2), topVault = world.rawTile(5, 5);

        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        conveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        router.setBlock(Blocks.router, Team.sharded);
        router.build.configureAny(Items.coal);
        leftConveyor.setBlock(Blocks.conveyor, Team.sharded, 2);
        rightConveyor.setBlock(Blocks.conveyor, Team.sharded, 0);
        middleConveyor.setBlock(Blocks.conveyor, Team.sharded, 1);
        leftVault.setBlock(Blocks.vault, Team.sharded);
        rightVault.setBlock(Blocks.vault, Team.sharded);
        topVault.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, rightVault.build.items.first());
        assertEquals(Items.coal, topVault.build.items.first());
        assertEquals(Items.coal, leftVault.build.items.first());
    }

    @Test
    void junctionOutputCorrect() {
        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        Tile source1 = world.rawTile(5,0),source2 = world.rawTile(7, 2),  conveyor1 = world.rawTile(5, 1),
        conveyor2 = world.rawTile(6,2), junction = world.rawTile(5, 2), conveyor3 = world.rawTile(5,3),
        conveyor4 = world.rawTile(4,2), vault2 = world.rawTile(3, 1), vault1 = world.rawTile(5,5);
        source1.setBlock(Blocks.itemSource, Team.sharded);
        source1.build.configureAny(Items.coal);
        source2.setBlock(Blocks.itemSource, Team.sharded);
        source2.build.configureAny(Items.copper);
        conveyor1.setBlock(Blocks.conveyor, Team.sharded, 1);
        conveyor2.setBlock(Blocks.conveyor, Team.sharded, 2);
        conveyor3.setBlock(Blocks.conveyor, Team.sharded, 1);
        conveyor4.setBlock(Blocks.conveyor, Team.sharded, 2);
        junction.setBlock(Blocks.junction, Team.sharded);

        vault1.setBlock(Blocks.vault, Team.sharded);
        vault2.setBlock(Blocks.vault, Team.sharded);

        updateBlocks(200);
        assertEquals(Items.coal, vault1.build.items.first());
        assertEquals(Items.copper, vault2.build.items.first());
    }

    @Test
    void blockOverlapRemoved(){
        world.loadMap(testMap);
        state.set(State.playing);

        //edge block
        world.tile(1, 1).setBlock(Blocks.coreShard);
        assertEquals(Blocks.coreShard, world.tile(0, 0).block());

        //this should overwrite the block
        world.tile(2, 2).setBlock(Blocks.coreShard);
        assertEquals(Blocks.air, world.tile(0, 0).block());
    }

    @Test
    void conveyorCrash(){
        world.loadMap(testMap);
        state.set(State.playing);

        world.tile(0, 0).setBlock(Blocks.conveyor);
        world.tile(0, 0).build.acceptStack(Items.copper, 1000, null);
    }

    @Test
    void conveyorBench(){
        int[] itemsa = {0};

        world.loadMap(testMap);
        state.set(State.playing);
        state.rules.limitMapArea = false;
        int length = 128;
        world.tile(0, 0).setBlock(Blocks.itemSource, Team.sharded);
        world.tile(0, 0).build.configureAny(Items.copper);

        Seq<Building> entities = Seq.with(world.tile(0, 0).build);

        for(int i = 0; i < length; i++){
            world.tile(i + 1, 0).setBlock(Blocks.conveyor, Team.sharded, 0);
            entities.add(world.tile(i + 1, 0).build);
        }

        world.tile(length + 1, 0).setBlock(new Block("___"){{
            hasItems = true;
            destructible = true;
            buildType = () -> new Building(){
                @Override
                public void handleItem(Building source, Item item){
                    itemsa[0] ++;
                }

                @Override
                public boolean acceptItem(Building source, Item item){
                    return true;
                }
            };
        }}, Team.sharded);

        entities.each(Building::updateProximity);

        //warmup
        for(int i = 0; i < 100000; i++){
            entities.each(Building::update);
        }

        Time.mark();
        for(int i = 0; i < 200000; i++){
            entities.each(Building::update);
        }
        Log.info(Time.elapsed() + "ms to process " + itemsa[0] + " items");
        assertNotEquals(0, itemsa[0]);
    }

    @Test
    void load77Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("77.msav"));

        //just tests if the map was loaded properly and didn't crash, no validity checks currently
        assertEquals(276, world.width());
        assertEquals(10, world.height());
    }

    @Test
    void load85Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("85.msav"));

        assertEquals(250, world.width());
        assertEquals(300, world.height());
    }

    @Test
    void load108Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("108.msav"));

        assertEquals(256, world.width());
        assertEquals(256, world.height());
    }

    @Test
    void load114Save(){
        resetWorld();
        SaveIO.load(Core.files.internal("114.msav"));

        assertEquals(500, world.width());
        assertEquals(500, world.height());
    }

    @Test
    void arrayIterators(){
        Seq<String> arr = Seq.with("a", "b" , "c", "d", "e", "f");
        Seq<String> results = new Seq<>();

        for(String s : arr);
        for(String s : results);

        Seq.iteratorsAllocated = 0;

        //simulate non-enhanced for loops, which should be correct

        for(int i = 0; i < arr.size; i++){
            for(int j = 0; j < arr.size; j++){
                results.add(arr.get(i) + arr.get(j));
            }
        }

        int index = 0;

        //test nested for loops
        for(String s : arr){
            for(String s2 : arr){
                assertEquals(results.get(index++), s + s2);
            }
        }

        assertEquals(results.size, index);
        assertEquals(0, Seq.iteratorsAllocated, "No new iterators must have been allocated.");
    }

    @Test
    void inventoryDeposit(){
        depositTest(Blocks.surgeSmelter, Items.copper);
        depositTest(Blocks.vault, Items.copper);
        depositTest(Blocks.thoriumReactor, Items.thorium);
    }

    @Test
    void edges(){
        Point2[] edges = Edges.getEdges(1);
        assertEquals(edges[0], new Point2(1, 0));
        assertEquals(edges[1], new Point2(0, 1));
        assertEquals(edges[2], new Point2(-1, 0));
        assertEquals(edges[3], new Point2(0, -1));

        Point2[] edges2 = Edges.getEdges(2);
        assertEquals(8, edges2.length);
    }

    @Test
    void buildingOverlap(){
        initBuilding();

        Unit d1 = UnitTypes.poly.create(Team.sharded);
        Unit d2 = UnitTypes.poly.create(Team.sharded);

        //infinite build range
        state.rules.editor = true;
        state.rules.infiniteResources = true;
        state.rules.buildSpeedMultiplier = 999999f;

        d1.set(0f, 0f);
        d2.set(20f, 20f);

        d1.addBuild(new BuildPlan(0, 0, 0, Blocks.copperWallLarge));
        d2.addBuild(new BuildPlan(1, 1, 0, Blocks.copperWallLarge));

        d1.update();
        d2.update();

        assertEquals(Blocks.copperWallLarge, world.tile(0, 0).block());
        assertEquals(Blocks.air, world.tile(2, 2).block());
        assertEquals(Blocks.copperWallLarge, world.tile(1, 1).block());
        assertEquals(world.tile(1, 1).build, world.tile(0, 0).build);
    }

    @Test
    void buildingDestruction(){
        initBuilding();

        Builderc d1 = UnitTypes.poly.create(Team.sharded);
        Builderc d2 = UnitTypes.poly.create(Team.sharded);

        d1.set(10f, 20f);
        d2.set(10f, 20f);

        d1.addBuild(new BuildPlan(0, 0, 0, Blocks.copperWallLarge));
        d2.addBuild(new BuildPlan(1, 1));

        Time.setDeltaProvider(() -> 3f);
        d1.update();
        Time.setDeltaProvider(() -> 1f);
        d2.update();

        assertEquals(content.getByName(ContentType.block, "build2"), world.tile(0, 0).block());

        Time.setDeltaProvider(() -> 9999f);

        //prevents range issues
        state.rules.infiniteResources = true;

        d1.update();

        assertEquals(Blocks.copperWallLarge, world.tile(0, 0).block());
        assertEquals(Blocks.copperWallLarge, world.tile(1, 1).block());

        d2.clearBuilding();
        d2.addBuild(new BuildPlan(1, 1));

        for(int i = 0; i < 3; i++){
            d2.update();
        }

        assertEquals(Blocks.air, world.tile(0, 0).block());
        assertEquals(Blocks.air, world.tile(2, 2).block());
        assertEquals(Blocks.air, world.tile(1, 1).block());
    }

    @Test
    void allBlockTest(){
        Tiles tiles = world.resize(80, 80);

        world.beginMapLoad();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
            }
        }
        int maxHeight = 0;
        state.rules.canGameOver = false;
        state.rules.borderDarkness = false;

        for(int x = 0, y = 0, i = 0; i < content.blocks().size; i ++){
            Block block = content.block(i);
            if(block.canBeBuilt()){
                int offset = Math.max(block.size % 2 == 0 ? block.size/2 - 1 : block.size/2, 0);

                if(x + block.size + 1 >= world.width()){
                    y += maxHeight;
                    maxHeight = 0;
                    x = 0;
                }

                tiles.get(x + offset, y + offset).setBlock(block);
                x += block.size;
                maxHeight = Math.max(maxHeight, block.size);
            }
        }
        world.endMapLoad();

        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                Tile tile = world.rawTile(x, y);
                if(tile.build != null){
                    try{
                        tile.build.update();
                    }catch(Throwable t){
                        fail("Failed to update block '" + tile.block() + "'.", t);
                    }
                    assertEquals(tile.block(), tile.build.block);
                    assertEquals(tile.block().health, tile.build.health());
                }
            }
        }
    }

    void checkPayloads(){
        for(int x = 0; x < world.tiles.width; x++){
            for(int y = 0; y < world.tiles.height; y++){
                Tile tile = world.rawTile(x, y);
                if(tile.build != null && tile.isCenter() && !(tile.block() instanceof CoreBlock)){
                    try{
                        tile.build.update();
                    }catch(Throwable t){
                        fail("Failed to update block in payload: '" + ((BuildPayload)tile.build.getPayload()).block() + "'", t);
                    }
                    assertEquals(tile.block(), tile.build.block);
                    assertEquals(tile.block().health, tile.build.health());
                }
            }
        }
    }

    @Test
    void allPayloadBlockTest(){
        int ts = 20;
        Tiles tiles = world.resize(ts * 3, ts * 3);

        world.beginMapLoad();
        for(int x = 0; x < tiles.width; x++){
            for(int y = 0; y < tiles.height; y++){
                tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
            }
        }

        tiles.getn(tiles.width - 2, tiles.height - 2).setBlock(Blocks.coreShard, Team.sharded);

        Seq<Block> blocks = content.blocks().select(b -> b.canBeBuilt());
        for(int i = 0; i < blocks.size; i++){
            int x = (i % ts) * 3 + 1;
            int y = (i / ts) * 3 + 1;
            Tile tile = tiles.get(x, y);
            tile.setBlock(Blocks.payloadConveyor, Team.sharded);
            Building build = tile.build;
            build.handlePayload(build, new BuildPayload(blocks.get(i), Team.sharded));
        }
        world.endMapLoad();

        checkPayloads();

        SaveIO.write(saveDirectory.child("payloads.msav"));
        logic.reset();
        SaveIO.load(saveDirectory.child("payloads.msav"));

        checkPayloads();
    }

    @TestFactory
    DynamicTest[] testSectorValidity(){
        Seq<DynamicTest> out = new Seq<>();
        if(world == null) world = new World();

        for(SectorPreset zone : content.sectors()){

            out.add(dynamicTest(zone.name, () -> {
                Time.setDeltaProvider(() -> 1f);

                logic.reset();
                state.rules.sector = zone.sector;
                try{
                    world.loadGenerator(zone.generator.map.width, zone.generator.map.height, zone.generator::generate);
                }catch(SaveException e){
                    //fails randomly and I don't care about fixing it
                    e.printStackTrace();
                    return;
                }
                zone.rules.get(state.rules);
                ObjectSet<Item> resources = new ObjectSet<>();
                boolean hasSpawnPoint = false;

                for(Tile tile : world.tiles){
                    if(tile.drop() != null){
                        resources.add(tile.drop());
                    }
                    if(tile.block() instanceof CoreBlock && tile.team() == state.rules.defaultTeam){
                        hasSpawnPoint = true;
                    }
                }

                if(state.rules.waves){
                    Seq<SpawnGroup> spawns = state.rules.spawns;

                    int bossWave = 0;
                    if(state.rules.winWave > 0){
                        bossWave = state.rules.winWave;
                    }else{
                        outer:
                        for(int i = 1; i <= 1000; i++){
                            for(SpawnGroup spawn : spawns){
                                if(spawn.effect == StatusEffects.boss && spawn.getSpawned(i) > 0){
                                    bossWave = i;
                                    break outer;
                                }
                            }
                        }
                    }

                    if(state.rules.attackMode){
                        bossWave = 100;
                    }else{
                        assertNotEquals(0, bossWave, "Sector " + zone.name + " doesn't have a boss/end wave.");
                    }

                    if(state.rules.winWave > 0) bossWave = state.rules.winWave - 1;

                    //TODO check for difficulty?
                    for(int i = 1; i <= bossWave; i++){
                        int total = 0;
                        for(SpawnGroup spawn : spawns){
                            total += spawn.getSpawned(i - 1);
                        }

                        assertNotEquals(0, total, "Sector " + zone + " has no spawned enemies at wave " + i);
                        //TODO this is flawed and needs to be changed later
                        //assertTrue(total < 75, "Sector spawns too many enemies at wave " + i + " (" + total + ")");
                    }
                }

                assertEquals(1, Team.sharded.cores().size, "Sector must have one core: " + zone);

                assertTrue(hasSpawnPoint, "Sector \"" + zone.name + "\" has no spawn points.");
                assertTrue(spawner.countSpawns() > 0 || (state.rules.attackMode && state.rules.waveTeam.data().hasCore()), "Sector \"" + zone.name + "\" has no enemy spawn points: " + spawner.countSpawns());
            }));
        }

        return out.toArray(DynamicTest.class);
    }

    /** Create a building */
    void initBuilding(){
        createMap();

        Tile core = world.tile(5, 5);
        core.setBlock(Blocks.coreShard, Team.sharded, 0);
        for(Item item : content.items()){
            core.build.items.set(item, 3000);
        }

        assertEquals(core.build, Team.sharded.data().core());
    }

    /** Test aimed at depositing item into given block. Gets run in test factories. */
    void depositTest(Block block, Item item){
        Unit unit = UnitTypes.mono.create(Team.sharded);
        Tile tile = new Tile(0, 0, Blocks.air, Blocks.air, block);
        tile.setTeam(Team.sharded);
        int capacity = tile.block().itemCapacity;

        assertNotNull(tile.build, "Tile should have an entity, but does not: " + tile);

        int deposited = tile.build.acceptStack(item, capacity - 1, unit);
        assertEquals(capacity - 1, deposited);

        tile.build.handleStack(item, capacity - 1, unit);
        assertEquals(tile.build.items.get(item), capacity - 1);

        int overflow = tile.build.acceptStack(item, 10, unit);
        assertEquals(1, overflow);

        tile.build.handleStack(item, 1, unit);
        assertEquals(capacity, tile.build.items.get(item));
    }
}
