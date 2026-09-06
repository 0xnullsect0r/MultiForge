/*
 * MultiForge — Copyright (c) 2026 MultiForge authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package net.multiforge.runtime.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import net.multiforge.runtime.region.AdaptiveSizingDriver.Recommendation;
import net.multiforge.runtime.region.AdaptiveSizingDriver.RegionSample;
import org.junit.jupiter.api.Test;

class AdaptiveSizingDriverTest {

    private final AdaptiveSizingDriver driver = new AdaptiveSizingDriver(AdaptiveSizingDriver.Config.defaults());

    @Test
    void hotRegionRecommendsSplit() {
        var s = new RegionSample(new RegionId(1), 40.0, 60.0, 8, 4);
        var r = driver.recommend(List.of(s));
        assertThat(r).singleElement().satisfies(rec -> assertThat(rec.recommendation())
                .isEqualTo(Recommendation.SPLIT));
    }

    @Test
    void coldPlayerlessRegionRecommendsMerge() {
        var s = new RegionSample(new RegionId(2), 3.0, 4.0, 3, 0);
        var r = driver.recommend(List.of(s));
        // player-only mode → PARK dominates before MERGE.
        assertThat(r).singleElement().satisfies(rec -> assertThat(rec.recommendation())
                .isEqualTo(Recommendation.PARK));
    }

    @Test
    void coldRegionInFullWorldModeMerges() {
        var driver2 = new AdaptiveSizingDriver(new AdaptiveSizingDriver.Config(35.0, 5.0, 4, false));
        var s = new RegionSample(new RegionId(3), 3.0, 4.0, 3, 0);
        var r = driver2.recommend(List.of(s));
        assertThat(r).singleElement().satisfies(rec -> assertThat(rec.recommendation())
                .isEqualTo(Recommendation.MERGE));
    }

    @Test
    void regionInBudgetHolds() {
        var s = new RegionSample(new RegionId(4), 20.0, 30.0, 8, 2);
        var r = driver.recommend(List.of(s));
        assertThat(r).singleElement().satisfies(rec -> assertThat(rec.recommendation())
                .isEqualTo(Recommendation.HOLD));
    }

    @Test
    void smallHotRegionHoldsRatherThanSplit() {
        // Only 2 sections — below minSectionsForSplit=4.
        var s = new RegionSample(new RegionId(5), 60.0, 80.0, 2, 4);
        var r = driver.recommend(List.of(s));
        assertThat(r).singleElement().satisfies(rec -> assertThat(rec.recommendation())
                .isEqualTo(Recommendation.HOLD));
    }
}
