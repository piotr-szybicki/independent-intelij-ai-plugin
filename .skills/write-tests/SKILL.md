---
name: write-tests
description: Generate unit tests for a given class or function, covering the happy path, edge cases, and error conditions.
---

When the user asks you to write tests, follow these steps:

1. Read the code under test and identify its public API.
2. For each public method or function, write tests covering:
   - The happy path with typical inputs.
   - Boundary values (empty collections, zero, null where nullable).
   - Error conditions (invalid input, expected exceptions).
3. Use JUnit 5 and AssertJ for assertions.
4. Name each test method so it reads as a sentence describing the expected behaviour.
5. Keep each test focused on one behaviour -- prefer many small tests over few large ones.
6. Do not mock anything unless the dependency performs I/O; prefer real objects.
