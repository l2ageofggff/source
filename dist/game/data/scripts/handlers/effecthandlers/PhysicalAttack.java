/*
 * This file is part of the L2J Mobius project.
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package handlers.effecthandlers;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.l2jmobius.Config;
import org.l2jmobius.gameserver.enums.ShotType;
import org.l2jmobius.gameserver.model.StatSet;
import org.l2jmobius.gameserver.model.actor.Attackable;
import org.l2jmobius.gameserver.model.actor.Creature;
import org.l2jmobius.gameserver.model.effects.AbstractEffect;
import org.l2jmobius.gameserver.model.effects.EffectType;
import org.l2jmobius.gameserver.model.item.instance.Item;
import org.l2jmobius.gameserver.model.skill.AbnormalType;
import org.l2jmobius.gameserver.model.skill.Skill;
import org.l2jmobius.gameserver.model.stats.Formulas;
import org.l2jmobius.gameserver.model.stats.Stat;

/**
 * Physical Attack effect implementation.<br>
 * Current formulas were tested to be the best matching retail, damage appears to be identical:<br>
 * For melee skills: 70 * graciaSkillBonus1.10113 * (patk * lvlmod + power) * crit * ss * skillpowerbonus / pdef<br>
 * For ranged skills: 70 * (patk * lvlmod + power + patk + power) * crit * ss * skillpower / pdef<br>
 * @author Nik
 */
public class PhysicalAttack extends AbstractEffect
{
	private final double _power;
	private final double _pAtkMod;
	private final double _pDefMod;
	private final double _criticalChance;
	private final boolean _ignoreShieldDefence;
	private final boolean _overHit;
	private final Set<AbnormalType> _abnormals;
	private final double _abnormalDamageMod;
	private final double _abnormalPowerMod;
	
	public PhysicalAttack(StatSet params)
	{
		_power = params.getDouble("power", 0);
		_pAtkMod = params.getDouble("pAtkMod", 1.0);
		_pDefMod = params.getDouble("pDefMod", 1.0);
		_criticalChance = params.getDouble("criticalChance", 10);
		_ignoreShieldDefence = params.getBoolean("ignoreShieldDefence", false);
		_overHit = params.getBoolean("overHit", false);
		final String abnormals = params.getString("abnormalType", null);
		if ((abnormals != null) && !abnormals.isEmpty())
		{
			_abnormals = new HashSet<>();
			for (String slot : abnormals.split(";"))
			{
				_abnormals.add(AbnormalType.getAbnormalType(slot));
			}
		}
		else
		{
			_abnormals = Collections.<AbnormalType> emptySet();
		}
		_abnormalDamageMod = params.getDouble("damageModifier", 1);
		_abnormalPowerMod = params.getDouble("powerModifier", 1);
	}
	
	@Override
	public boolean calcSuccess(Creature effector, Creature effected, Skill skill)
	{
		return !Formulas.calcPhysicalSkillEvasion(effector, effected, skill);
	}
	
	@Override
	public EffectType getEffectType()
	{
		return EffectType.PHYSICAL_ATTACK;
	}
	
	@Override
	public boolean isInstant()
	{
		return true;
	}
	
	@Override
	public void instant(Creature effector, Creature effected, Skill skill, Item item)
	{
		if (effector.isAlikeDead())
		{
			return;
		}
		
		if (effected.isPlayer() && effected.getActingPlayer().isFakeDeath() && Config.FAKE_DEATH_DAMAGE_STAND)
		{
			effected.stopFakeDeath(true);
		}
		
		if (_overHit && effected.isAttackable())
		{
			((Attackable) effected).overhitEnabled(true);
		}
		
		final double attack = effector.getPAtk() * _pAtkMod;
		double defence = effected.getPDef() * _pDefMod;
		
		if (!_ignoreShieldDefence)
		{
			switch (Formulas.calcShldUse(effector, effected))
			{
				case Formulas.SHIELD_DEFENSE_SUCCEED:
				{
					defence += effected.getShldDef();
					break;
				}
				case Formulas.SHIELD_DEFENSE_PERFECT_BLOCK:
				{
					defence = -1;
					break;
				}
			}
		}
		
		double damage = 1;
		final boolean critical = Formulas.calcCrit(_criticalChance, effector, effected, skill);
		
		if (defence != -1)
		{
			// Trait, elements
			final double weaponTraitMod = Formulas.calcWeaponTraitBonus(effector, effected);
			final double generalTraitMod = Formulas.calcGeneralTraitBonus(effector, effected, skill.getTraitType(), true);
			final double weaknessMod = Formulas.calcWeaknessBonus(effector, effected, skill.getTraitType());
			final double attributeMod = Formulas.calcAttributeBonus(effector, effected, skill);
			final double pvpPveMod = Formulas.calculatePvpPveBonus(effector, effected, skill, true);
			final double randomMod = effector.getRandomDamageMultiplier();
			
			// Skill specific mods.
			boolean hasAbnormalType = false;
			if (!_abnormals.isEmpty())
			{
				for (AbnormalType abnormal : _abnormals)
				{
					if (effected.hasAbnormalType(abnormal))
					{
						hasAbnormalType = true;
						break;
					}
				}
			}
			final double power = ((_power * (hasAbnormalType ? _abnormalPowerMod : 1)) + effector.getStat().getValue(Stat.SKILL_POWER_ADD, 0));
			final double weaponMod = effector.getAttackType().isRanged() ? 70 : 77;
			final double rangedBonus = effector.getAttackType().isRanged() ? attack + power : 0;
			final double critMod = critical ? Formulas.calcCritDamage(effector, effected, skill) : 1;
			double ssmod = 1;
			if (skill.useSoulShot())
			{
				if (effector.isChargedShot(ShotType.SOULSHOTS))
				{
					ssmod = 2 * effector.getStat().getValue(Stat.SHOTS_BONUS); // 2.04 for dual weapon?
				}
				else if (effector.isChargedShot(ShotType.BLESSED_SOULSHOTS))
				{
					ssmod = 4 * effector.getStat().getValue(Stat.SHOTS_BONUS);
				}
			}
			
			// ...................____________Melee Damage_____________......................................___________________Ranged Damage____________________
			// ATTACK CALCULATION 77 * ((pAtk * lvlMod) + power) / pdef            RANGED ATTACK CALCULATION 70 * ((pAtk * lvlMod) + power + patk + power) / pdef
			// ```````````````````^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^``````````````````````````````````````^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
			final double baseMod = (weaponMod * ((attack * effector.getLevelMod()) + power + rangedBonus)) / defence;
			damage = baseMod * (hasAbnormalType ? _abnormalDamageMod : 1) * ssmod * critMod * weaponTraitMod * generalTraitMod * weaknessMod * attributeMod * pvpPveMod * randomMod;
			damage *= effector.getStat().getValue(Stat.PHYSICAL_SKILL_POWER, 1);
		}
		
		effector.doAttack(damage, effected, skill, false, false, critical, false);
	}
}
