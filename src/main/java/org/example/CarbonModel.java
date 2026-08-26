package org.example;

/**
 * Simulated carbon-intensity model for green-cloud experiments.
 *
 * Values are fixed experiment profiles, not live grid data. Using different
 * intensities per VM lets the scheduler distinguish energy-aware decisions
 * from carbon-aware decisions.
 */
public class CarbonModel {
    private final double[] carbonGramsPerKWhByVm;

    public CarbonModel(double[] carbonGramsPerKWhByVm) {
        this.carbonGramsPerKWhByVm = carbonGramsPerKWhByVm.clone();
    }

    public static CarbonModel mixedIntensityProfile() {
        return new CarbonModel(new double[] {
                700.0, 475.0, 300.0, 250.0, 200.0
        });
    }

    public double intensityForVm(int vmIndex) {
        if (vmIndex < carbonGramsPerKWhByVm.length) {
            return carbonGramsPerKWhByVm[vmIndex];
        }
        return 475.0;
    }

    public double referenceIntensity() {
        return 475.0;
    }

    public double averageIntensity() {
        double total = 0.0;
        for (double v : carbonGramsPerKWhByVm) total += v;
        return carbonGramsPerKWhByVm.length == 0 ? referenceIntensity() : total / carbonGramsPerKWhByVm.length;
    }
}
