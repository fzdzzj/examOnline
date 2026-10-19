package com.exam.adaptive.support;

import java.util.List;

/**
 * IRT 三参数 Logistic（3PL）数值核心（spec add-irt-adaptive-testing）——纯函数、无状态。
 *
 * <p>3PL 作答概率：{@code P(θ) = c + (1 - c) / (1 + exp(-a(θ - b)))}。
 * θ 估计以 <b>EAP（期望后验）为主</b>：先验取标准正态 N(0,1)，[-4, 4] 等距网格（步长 0.05）
 * 上做后验加权求均值与标准差；{@link #newtonRaphson(List)} 为辅助估计器（Fisher scoring），
 * 在 EAP 网格退化（后验权重全下溢）时兜底，并供单测独立验证收敛性。
 *
 * <p>信息量（选题依据）：
 * {@code I(θ) = a² (P - c)² (1 - P) / (P (1 - c)²)}；
 * 2PL（c=0）时退化为 {@code a² P (1 - P)}，符合经典测量学定义。
 */
public final class IrtEstimator {

    /** EAP 网格下界 */
    public static final double THETA_MIN = -4.0;
    /** EAP 网格上界 */
    public static final double THETA_MAX = 4.0;
    /** EAP 网格步长 */
    public static final double THETA_STEP = 0.05;
    /** θ 初值（无作答时取先验均值） */
    public static final double PRIOR_MEAN = 0.0;
    /** θ 初值方差的平方根（无作答时 SE 即先验标准差） */
    public static final double PRIOR_SD = 1.0;

    private static final double EPS = 1e-9;

    private IrtEstimator() {
    }

    /** 单题作答记录（a/b/c 为该题 3PL 参数，correct 为本次判分结果）。 */
    public record Administered(double a, double b, double c, boolean correct) {
    }

    /** θ 估计结果（theta 能力值，se 标准误）。 */
    public record Estimate(double theta, double se) {
    }

    /** 3PL 作答正确概率。 */
    public static double probability(Administered item, double theta) {
        return probability(item.a(), item.b(), item.c(), theta);
    }

    /** 3PL 作答正确概率（参数显式传入，供单测与标定校验共用）。 */
    public static double probability(double a, double b, double c, double theta) {
        double cClamped = clamp(c, 0.0, 0.99);
        double z = -a * (theta - b);
        double logistic = 1.0 / (1.0 + Math.exp(z));
        return cClamped + (1.0 - cClamped) * logistic;
    }

    /** Fisher 信息量 I(θ)：CAT 选题依据（值越大对该能力段的区分力越强）。 */
    public static double information(Administered item, double theta) {
        double c = clamp(item.c(), 0.0, 0.99);
        double p = clamp(probability(item, theta), EPS, 1 - EPS);
        double pMinusC = p - c;
        return item.a() * item.a() * pMinusC * pMinusC * (1 - p) / (p * (1 - c) * (1 - c));
    }

    /**
     * EAP 期望后验估计（主估计器）：先验 N(0,1) × 各题似然的乘积在 θ 网格上归一化，
     * 返回后验均值与后验标准差。无作答时返回先验（θ=0，SE=1）。
     */
    public static Estimate eap(List<Administered> items) {
        if (items == null || items.isEmpty()) {
            return new Estimate(PRIOR_MEAN, PRIOR_SD);
        }
        int size = (int) Math.round((THETA_MAX - THETA_MIN) / THETA_STEP) + 1;
        double[] grid = new double[size];
        double[] logW = new double[size];
        double maxLogW = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < size; i++) {
            double theta = THETA_MIN + i * THETA_STEP;
            grid[i] = theta;
            double logPrior = -0.5 * theta * theta;
            double logLik = 0.0;
            for (Administered item : items) {
                double p = clamp(probability(item, theta), EPS, 1 - EPS);
                logLik += item.correct() ? Math.log(p) : Math.log(1 - p);
            }
            logW[i] = logPrior + logLik;
            if (logW[i] > maxLogW) {
                maxLogW = logW[i];
            }
        }
        // 后验权重全下溢（极端作答序列）→ 数值兜底走 Newton-Raphson
        if (!Double.isFinite(maxLogW)) {
            return newtonRaphson(items);
        }
        double sumW = 0.0;
        double sumThetaW = 0.0;
        double[] w = new double[size];
        for (int i = 0; i < size; i++) {
            w[i] = Math.exp(logW[i] - maxLogW);
            sumW += w[i];
            sumThetaW += grid[i] * w[i];
        }
        if (sumW <= 0 || !Double.isFinite(sumW)) {
            return newtonRaphson(items);
        }
        double mean = sumThetaW / sumW;
        double varSum = 0.0;
        for (int i = 0; i < size; i++) {
            double diff = grid[i] - mean;
            varSum += diff * diff * w[i];
        }
        double sd = Math.sqrt(Math.max(varSum / sumW, 0.0));
        return new Estimate(mean, sd);
    }

    /**
     * Newton-Raphson / Fisher scoring 辅助估计器（无先验 MLE）：
     * 迭代 {@code θ ← θ + L'(θ) / I(θ)}，其中 {@code L'(θ) = Σ a(u - P)(P - c) / (P(1 - c))}，
     * 至步长 &lt; 1e-6 或满 50 次；SE 取 {@code 1 / sqrt(I(θ))}。信息量为 0 时回退先验。
     */
    public static Estimate newtonRaphson(List<Administered> items) {
        if (items == null || items.isEmpty()) {
            return new Estimate(PRIOR_MEAN, PRIOR_SD);
        }
        double theta = PRIOR_MEAN;
        double info = 0.0;
        for (int iter = 0; iter < 50; iter++) {
            double score = 0.0;
            info = 0.0;
            for (Administered item : items) {
                double c = clamp(item.c(), 0.0, 0.99);
                double p = clamp(probability(item, theta), EPS, 1 - EPS);
                double u = item.correct() ? 1.0 : 0.0;
                score += item.a() * (u - p) * (p - c) / (p * (1 - c));
                info += information(item, theta);
            }
            if (info <= EPS) {
                break;
            }
            double step = score / info;
            double next = clamp(theta + step, THETA_MIN - 2.0, THETA_MAX + 2.0);
            double delta = Math.abs(next - theta);
            theta = next;
            if (delta < 1e-6) {
                break;
            }
        }
        double se = info > EPS ? 1.0 / Math.sqrt(info) : PRIOR_SD;
        return new Estimate(theta, se);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
