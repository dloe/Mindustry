import arc.struct.Seq;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.game.SpawnGroup;
import mindustry.game.Waves;
import mindustry.type.ItemStack;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

import static mindustry.content.UnitTypes.*;
import static mindustry.content.UnitTypes.toxopid;

public class WavesStub extends Waves {

    public float difficulty;
    public  WavesStub(float difficulty)
    {
        this.difficulty = difficulty;
    }
}
