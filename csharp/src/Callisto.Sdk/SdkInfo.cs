namespace Callisto.Sdk;

/// <summary>SDK identity constants. Keep <see cref="Version"/> in sync with the .csproj.</summary>
public static class SdkInfo
{
    /// <summary>The package version (must equal <c>&lt;Version&gt;</c> in Callisto.Sdk.csproj).</summary>
    public const string Version = "0.2.0";

    /// <summary>The OpenTelemetry <c>telemetry.sdk.language</c> name for .NET.</summary>
    public const string Language = "dotnet";

    /// <summary>The <c>telemetry.sdk.name</c> and instrumentation scope name.</summary>
    public const string Name = "callisto-sdk";

    /// <summary>The <c>User-Agent</c> sent with log requests.</summary>
    public const string UserAgent = "callisto-sdk-" + Language + "/" + Version;
}
