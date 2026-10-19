package com.exam.adaptive.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3PL 数值核心单测（spec add-irt-adaptive-testing，纯函数无 Spring/Mockito）：
 *
 * <ul>
 *   <li>作答概率与 Fisher 信息量公式：a 越大、b 越接近 θ 的题信息量越大；</li>
 *   <li>EAP：无作答返回先验（θ=0, SE=1）；答对抬高 θ；题量增加使 SE 单调下降；</li>
 *   <li>Newton-Raphson（辅助估计器）：对 b=1 的题连续答对可收敛到正区间；</li>
 *   <li>猜测系数 c 抬升基线概率、压低高能力段信息量。</li>
 * </ul>
 */
class IrtEstimatorTest {

    @Test
    @DisplayName("3PL 概率：θ=b 时 P=(1+c)/2；θ→∞ 趋近 1；c 越高基线越高")
    void probabilityFollowsThreeParameterLogistic() {
        IrtEstimator.Administered item = new IrtEstimator.Administered(1.5, 0.5, 0.2, true);
        // θ=b → logistic=0.5 → P = c + (1-c)*0.5 = 0.6
        assertEquals(0.6, IrtEstimator.probability(item, 0.5), 1e-9);
        assertTrue(IrtEstimator.probability(item, 6.0) > 0.99);
        assertTrue(IrtEstimator.probability(item, -6.0) < 0.21);

        IrtEstimator.Administered noGuess = new IrtEstimator.Administered(1.5, 0.5, 0.0, true);
        assertTrue(IrtEstimator.probability(item, -6.0) > IrtEstimator.probability(noGuess, -6.0));
    }

    @Test
    @DisplayName("Fisher 信息量：a 越大越高；b 贴近 θ 时最高；远离 θ 迅速衰减")
    void informationPeaksAtDifficultyNearTheta() {
        IrtEstimator.Administered strong = new IrtEstimator.Administered(2.0, 0.0, 0.0, true);
        IrtEstimator.Administered weak = new IrtEstimator.Administered(1.0, 0.0, 0.0, true);
        // 2PL 在 θ=b=0 时 I = a² * 0.25
        assertEquals(1.0, IrtEstimator.information(strong, 0.0), 1e-9);
        assertEquals(0.25, IrtEstimator.information(weak, 0.0), 1e-9);
        assertTrue(IrtEstimator.information(strong, 0.0) > IrtEstimator.information(strong, 2.5));

        IrtEstimator.Administered guessed = new IrtEstimator.Administered(2.0, 0.0, 0.25, true);
        assertTrue(IrtEstimator.information(guessed, 0.0) < IrtEstimator.information(strong, 0.0));
    }

    @Test
    @DisplayName("EAP：无作答返回先验；连续答对抬高 θ；题量增加使 SE 下降")
    void eapRaisesThetaAndShrinksSe() {
        IrtEstimator.Estimate prior = IrtEstimator.eap(List.of());
        assertEquals(0.0, prior.theta(), 1e-9);
        assertEquals(1.0, prior.se(), 1e-9);

        List<IrtEstimator.Administered> items = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            items.add(new IrtEstimator.Administered(1.5, 0.0, 0.0, true));
        }
        IrtEstimator.Estimate afterThree = IrtEstimator.eap(items);
        assertTrue(afterThree.theta() > 0.5);
        assertTrue(afterThree.se() < prior.se());

        items.add(new IrtEstimator.Administered(1.5, 0.0, 0.0, true));
        IrtEstimator.Estimate afterFour = IrtEstimator.eap(items);
        assertTrue(afterFour.se() < afterThree.se());
        assertTrue(afterFour.theta() > afterThree.theta());
    }

    @Test
    @DisplayName("Newton-Raphson 辅助估计器：对 b=1 的题连续答对，θ 收敛到正区间且 SE 有限")
    void newtonRaphsonConvergesForCorrectResponses() {
        List<IrtEstimator.Administered> items = List.of(
                new IrtEstimator.Administered(1.2, 1.0, 0.0, true),
                new IrtEstimator.Administered(1.2, 1.0, 0.0, true),
                new IrtEstimator.Administered(1.2, 1.0, 0.0, true),
                new IrtEstimator.Administered(1.2, 1.0, 0.0, false));
        IrtEstimator.Estimate estimate = IrtEstimator.newtonRaphson(items);
        assertTrue(estimate.theta() > 0.0);
        assertTrue(estimate.theta() < 4.0);
        assertTrue(estimate.se() > 0.0);
        assertTrue(Double.isFinite(estimate.se()));
    }
}
