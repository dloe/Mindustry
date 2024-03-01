package mindustry.core;

import arc.Core;
import arc.Events;
import arc.math.Mathf;
import arc.util.Tmp;
import mindustry.game.EventType;
import mindustry.game.SectorInfo;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.Call;
import mindustry.gen.Groups;
import mindustry.maps.SectorDamage;
import mindustry.type.Item;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock;

import java.util.Iterator;

import static mindustry.Vars.*;
import static mindustry.Vars.state;

//logic stub that extends the logic class
public class LogicStub {

    //override constructor, we want to instead call our event creations method
    public LogicStub()
    {
        //super();
        System.out.print("Stub constructor ran");
    }

    //add our constructEvents
    //dummy method that creates and constructs our logic events instead of having it run in constructor, this logic can
    //be pulled out and tested.
    public void ConstructLogicEvents()
    {
        Events.on(EventType.BlockDestroyEvent.class, event -> {
            //skip if rule is off
            if(!state.rules.ghostBlocks) return;

            //blocks that get broken are appended to the team's broken block queue
            Tile tile = event.tile;
            //skip null entities or un-rebuildables, for obvious reasons
            if(tile.build == null || !tile.block().rebuildable) return;

            tile.build.addPlan(true);
        });

        Events.on(EventType.BlockBuildEndEvent.class, event -> {
            if(!event.breaking){
                Teams.TeamData data = event.team.data();
                Iterator<Teams.BlockPlan> it = data.plans.iterator();
                var bounds = event.tile.block().bounds(event.tile.x, event.tile.y, Tmp.r1);
                while(it.hasNext()){
                    Teams.BlockPlan b = it.next();
                    Block block = content.block(b.block);
                    if(bounds.overlaps(block.bounds(b.x, b.y, Tmp.r2))){
                        b.removed = true;
                        it.remove();
                    }
                }

                if(event.team == state.rules.defaultTeam){
                    state.stats.placedBlockCount.increment(event.tile.block());
                }
            }
        });

        //when loading a 'damaged' sector, propagate the damage
        Events.on(EventType.SaveLoadEvent.class, e -> {
            if(state.isCampaign()){
                state.rules.coreIncinerates = true;

                //TODO why is this even a thing?
                state.rules.canGameOver = true;

                //fresh map has no sector info
                if(!e.isMap){
                    SectorInfo info = state.rules.sector.info;
                    info.write();

                    //only simulate waves if the planet allows it
                    if(state.rules.sector.planet.allowWaveSimulation){
                        //how much wave time has passed
                        int wavesPassed = info.wavesPassed;

                        //wave has passed, remove all enemies, they are assumed to be dead
                        if(wavesPassed > 0){
                            Groups.unit.each(u -> {
                                if(u.team == state.rules.waveTeam){
                                    u.remove();
                                }
                            });
                        }

                        //simulate passing of waves
                        if(wavesPassed > 0){
                            //simulate wave counter moving forward
                            state.wave += wavesPassed;
                            state.wavetime = state.rules.waveSpacing;

                            SectorDamage.applyCalculatedDamage();
                        }
                    }

                    state.getSector().planet.applyRules(state.rules);

                    //reset values
                    info.damage = 0f;
                    info.wavesPassed = 0;
                    info.hasCore = true;
                    info.secondsPassed = 0;

                    state.rules.sector.saveInfo();
                }
            }
        });

        Events.on(EventType.PlayEvent.class, e -> {
            //reset weather on play
            var randomWeather = state.rules.weather.copy().shuffle();
            float sum = 0f;
            for(var weather : randomWeather){
                weather.cooldown = sum + Mathf.random(weather.maxFrequency);
                sum += weather.cooldown;
            }
            //tick resets on new save play
            state.tick = 0f;
        });

        Events.on(EventType.WorldLoadEvent.class, e -> {
            //enable infinite ammo for wave team by default
            state.rules.waveTeam.rules().infiniteAmmo = true;

            if(state.isCampaign()){
                //enable building AI on campaign unless the preset disables it

                state.rules.coreIncinerates = true;
                state.rules.waveTeam.rules().infiniteResources = true;
                state.rules.waveTeam.rules().buildSpeedMultiplier *= state.getPlanet().enemyBuildSpeedMultiplier;

                //fill enemy cores by default? TODO decide
                for(var core : state.rules.waveTeam.cores()){
                    for(Item item : content.items()){
                        core.items.set(item, core.block.itemCapacity);
                    }
                }

                //set up hidden items
                state.rules.hiddenBuildItems.clear();
                state.rules.hiddenBuildItems.addAll(state.rules.sector.planet.hiddenItems);
            }

            //save settings
            Core.settings.manualSave();
        });

        //sync research
        Events.on(EventType.UnlockEvent.class, e -> {
            if(net.server()){
                Call.researched(e.content);
            }
        });

        Events.on(EventType.SectorCaptureEvent.class, e -> {
            if(!net.client() && e.sector == state.getSector() && e.sector.isBeingPlayed()){
                state.rules.waveTeam.data().destroyToDerelict();
            }
        });

        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if(e.tile.build instanceof CoreBlock.CoreBuild core && core.team.isAI() && state.rules.coreDestroyClear){
                Core.app.post(() -> {
                    core.team.data().timeDestroy(core.x, core.y, state.rules.enemyCoreBuildRadius);
                });
            }
        });

        //listen to core changes; if all cores have been destroyed, set to derelict.
        Events.on(EventType.CoreChangeEvent.class, e -> Core.app.post(() -> {
            if(state.rules.cleanupDeadTeams && state.rules.pvp && !e.core.isAdded() && e.core.team != Team.derelict && e.core.team.cores().isEmpty()){
                e.core.team.data().destroyToDerelict();
            }
        }));

        Events.on(EventType.BlockBuildEndEvent.class, e -> {
            if(e.team == state.rules.defaultTeam){
                if(e.breaking){
                    state.stats.buildingsDeconstructed++;
                }else{
                    state.stats.buildingsBuilt++;
                }
            }
        });

        Events.on(EventType.BlockDestroyEvent.class, e -> {
            if(e.tile.team() == state.rules.defaultTeam){
                state.stats.buildingsDestroyed ++;
            }
        });

        Events.on(EventType.UnitDestroyEvent.class, e -> {
            if(e.unit.team() != state.rules.defaultTeam){
                state.stats.enemyUnitsDestroyed ++;
            }
        });

        Events.on(EventType.UnitCreateEvent.class, e -> {
            if(e.unit.team == state.rules.defaultTeam){
                state.stats.unitsCreated++;
            }
        });
    }


}
