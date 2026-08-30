name := "test-project"
version := "0.1.0"
organization := "com.example"

// Minimal configuration for testing task availability
// (no actual network access needed for task existence checks)
skillsSources := Seq()
skillsToAdd := Seq()
skillsHarnesses := Seq("copilot")
skillsOutputDir := baseDirectory.value
