package com.bkanent.listing.comparison.model;

public record ListingComparisonLegacyInventory(int metadataFiles, int ownedFiles,
                                                int filesWithoutOwner, int pdfFiles) { }
