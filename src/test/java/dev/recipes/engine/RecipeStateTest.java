package dev.recipes.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeStateTest {

    @Test
    void initializesCorrectly() {
        var state = new RecipeState("test-recipe", "step-a");

        assertThat(state.recipeId()).isEqualTo("test-recipe");
        assertThat(state.currentStep()).isEqualTo("step-a");
        assertThat(state.stepCount()).isEqualTo(1);
        assertThat(state.visitCount("step-a")).isEqualTo(1);
        assertThat(state.sessionCreated()).isFalse();
    }

    @Test
    void transitionUpdatesState() {
        var state = new RecipeState("test-recipe", "step-a");

        state.transitionTo("step-b");

        assertThat(state.currentStep()).isEqualTo("step-b");
        assertThat(state.stepCount()).isEqualTo(2);
        assertThat(state.visitCount("step-a")).isEqualTo(1);
        assertThat(state.visitCount("step-b")).isEqualTo(1);
    }

    @Test
    void tracksMultipleVisitsToSameStep() {
        var state = new RecipeState("test-recipe", "review");

        state.transitionTo("fix");
        state.transitionTo("review");

        assertThat(state.visitCount("review")).isEqualTo(2);
        assertThat(state.stepCount()).isEqualTo(3);
    }

    @Test
    void retryCountResetsOnNewVisit() {
        var state = new RecipeState("test-recipe", "step-a");

        state.incrementRetry("step-a");
        assertThat(state.retryCount("step-a")).isEqualTo(1);

        state.transitionTo("step-b");
        state.transitionTo("step-a");
        assertThat(state.retryCount("step-a")).isEqualTo(0);
    }

    @Test
    void sessionCreatedFlag() {
        var state = new RecipeState("test-recipe", "step-a");

        assertThat(state.sessionCreated()).isFalse();
        state.markSessionCreated();
        assertThat(state.sessionCreated()).isTrue();
    }

    @Test
    void freshCreatesNewState() {
        var state = RecipeState.fresh("new-recipe", "initial");

        assertThat(state.recipeId()).isEqualTo("new-recipe");
        assertThat(state.currentStep()).isEqualTo("initial");
        assertThat(state.stepCount()).isEqualTo(1);
        assertThat(state.sessionCreated()).isFalse();
    }
}
