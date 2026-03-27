#include <stdio.h>
#include <stdlib.h>

typedef struct
{
    int id;
    char name[50];
    int age;
} User;

// Forward declaration for the mocked DB query function
void db_findUserAsync(char *userId, void (*callback)(User *, void *), void *statePointer);

// 1. You must manually define a struct to hold the "captured" variables
struct MyContinuationState
{
    char *userId;
    void (*k)(User *); // the "next" thing to do
};

// 2. You write a standard, non-closure function.
// It takes the result, PLUS a pointer to the state it needs.
void onDatabaseResponse(User *dbUser, void *statePointer)
{
    // Unpack the state manually
    struct MyContinuationState *state = (struct MyContinuationState *)statePointer;

    printf("Found %s\n", state->userId);

    // Call the next continuation
    state->k(dbUser);

    // Clean up memory (manual garbage collection)
    free(state);
}

// 3. The async caller must package up the struct manually
void processUser(char *userId, void (*k)(User *))
{
    struct MyContinuationState *state = malloc(sizeof(struct MyContinuationState));
    state->userId = userId;
    state->k = k;

    // Pass both the function pointer AND the state struct
    db_findUserAsync(userId, onDatabaseResponse, state);
}

void printUserDetails(User *u)
{
    if (u != NULL)
    {
        printf("User found: %s\n", u->name);
        printf("Age: %d\n", u->age);
    }
}

int main()
{
    processUser("user_123", printUserDetails);
    return 0;
}