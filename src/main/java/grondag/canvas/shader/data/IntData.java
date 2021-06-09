/*
 *  Copyright 2019, 2020 grondag
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License.  You may obtain a copy
 *  of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package grondag.canvas.shader.data;

import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;

import grondag.canvas.apiimpl.MaterialConditionImpl;
import grondag.fermion.bits.BitPacker32;

public final class IntData {
	private IntData() { }

	// PERF: should probably combine these into a single uint array

	public static final int WORLD_DATA_INDEX = 0;
	public static final int PLAYER_DATA_INDEX = 1;
	public static final int CONDITION_DATA_START = 2;
	public static final int INT_LENGTH = CONDITION_DATA_START + MaterialConditionImpl.CONDITION_FLAG_ARRAY_LENGTH;

	public static final IntBuffer INT_DATA = BufferUtils.createIntBuffer(INT_LENGTH);

	private static final BitPacker32<Void> WORLD_FLAGS = new BitPacker32<>(null, null);
	static final BitPacker32<Void>.BooleanElement FLAG_HAS_SKYLIGHT = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_OVERWORLD = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_NETHER = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_END = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_RAINING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_THUNDERING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_IS_SKY_DARKENED = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_EYE_IN_FLUID = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_EYE_IN_WATER = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_EYE_IN_LAVA = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SNEAKING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SWIMMING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SNEAKING_POSE = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SWIMMING_POSE = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_CREATIVE = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SPECTATOR = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_RIDING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_ON_FIRE = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SLEEPING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SPRINTING = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_WET = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_MOONLIT = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_CAMERA_IN_FLUID = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_CAMERA_IN_WATER = WORLD_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_CAMERA_IN_LAVA = WORLD_FLAGS.createBooleanElement();

	static final BitPacker32<Void> PLAYER_FLAGS = new BitPacker32<>(null, null);
	static final BitPacker32<Void>.BooleanElement FLAG_SPEED = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SLOWNESS = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_HASTE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_MINING_FATIGUE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_STRENGTH = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_INSTANT_HEALTH = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_INSTANT_DAMAGE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_JUMP_BOOST = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_NAUSEA = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_REGENERATION = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_RESISTANCE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_FIRE_RESISTANCE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_WATER_BREATHING = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_INVISIBILITY = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_BLINDNESS = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_NIGHT_VISION = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_HUNGER = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_WEAKNESS = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_POISON = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_WITHER = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_HEALTH_BOOST = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_ABSORPTION = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SATURATION = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_GLOWING = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_LEVITATION = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_LUCK = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_UNLUCK = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_SLOW_FALLING = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_CONDUIT_POWER = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_DOLPHINS_GRACE = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_BAD_OMEN = PLAYER_FLAGS.createBooleanElement();
	static final BitPacker32<Void>.BooleanElement FLAG_HERO_OF_THE_VILLAGE = PLAYER_FLAGS.createBooleanElement();

	public static final int UINT_COUNT = 1;
	public static final int RENDER_FRAMES = 0;
	public static final IntBuffer UINT_DATA = BufferUtils.createIntBuffer(UINT_COUNT);
}
