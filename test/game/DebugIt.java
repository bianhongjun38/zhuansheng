package com.zsb.plan;

import java.util.Random;

/** 临时调试：逐步打印 playCard 后的引擎状态，定位三连打断测试失败原因。 */
public class DebugIt {
    public static void main(String[] args) {
        GameData G = GameData.get(new android.content.Context());
        GBattle b = new GBattle(G, G.enemyById("devourer"), new Random(9), null);
        b.chooseAttack();
        System.out.println("hand=" + ids(b));
        for (int i = 0; i < 3 && !b.over(); i++) {
            GCard c = b.hand.get(Math.min(i, b.hand.size() - 1));
            int hp0 = b.enemyHp;
            int dmg = b.playCard(c);
            System.out.println("play#" + (i + 1) + " " + c.en
                    + " dmg=" + dmg + " (hp " + hp0 + "->" + b.enemyHp + ")"
                    + " phase=" + b.phase + " energy=" + b.energy
                    + " streak=" + b.streak + " interrupted=" + b.interruptedThisTurn
                    + " skipCharge=" + b.skipEnemyCharge
                    + " resonance=" + (b.resonance == null ? "-" : b.resonance.en)
                    + " lastWasReso=" + b.lastWasResonance
                    + " hand=" + ids(b));
            if (b.phase == GBattle.PHASE_PLAY && !b.hand.isEmpty() && b.energy > 0) {
                // 继续下一拍（模拟 UI 在同一回合继续出牌）
                continue;
            }
            System.out.println("  -> 引擎在拍内结束了回合");
            break;
        }
    }

    static String ids(GBattle b) {
        StringBuilder sb = new StringBuilder("[");
        for (GCard c : b.hand) sb.append(c.en).append(" ");
        return sb.append("]").toString();
    }
}
